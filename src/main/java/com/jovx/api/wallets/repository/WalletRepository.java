package com.jovx.api.wallets.repository;

import com.jovx.api.wallets.model.WalletModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local-postgres")
public final class WalletRepository {
  private static final String REQUEST_COLUMNS =
      "id,request_id,partner_id,kind,amount,method,reference,note,status,requested_by,created_at,"
          + "decided_by,decided_at,decision_reason,payout_method,payout_reference";
  private final JdbcTemplate jdbc;

  public WalletRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  /** Creates the wallet on first use, then locks it for the rest of the transaction. */
  public Wallet lockWallet(UUID partner, Instant now) {
    jdbc.update("INSERT INTO partner_wallets(partner_id,balance,held,updated_at) VALUES (?,0,0,?) ON CONFLICT (partner_id) DO NOTHING",
        partner.toString(), ts(now));
    return jdbc.queryForObject("SELECT partner_id,balance,held FROM partner_wallets WHERE partner_id=? FOR UPDATE", WALLET, partner.toString());
  }

  public Wallet wallet(UUID partner) {
    var rows = jdbc.query("SELECT partner_id,balance,held FROM partner_wallets WHERE partner_id=?", WALLET, partner.toString());
    return rows.isEmpty() ? new Wallet(partner, 0, 0) : rows.getFirst();
  }

  public Map<UUID, Wallet> wallets() {
    var result = new HashMap<UUID, Wallet>();
    for (var w : jdbc.query("SELECT partner_id,balance,held FROM partner_wallets", WALLET)) result.put(w.partnerId(), w);
    return result;
  }

  public Map<UUID, Integer> pendingCounts() {
    var result = new HashMap<UUID, Integer>();
    jdbc.query("SELECT partner_id,COUNT(*) FROM wallet_requests WHERE status='PENDING' GROUP BY partner_id",
        (org.springframework.jdbc.core.RowCallbackHandler) r -> result.put(uuid(r, 1), r.getInt(2)));
    return result;
  }

  /** Moves the spendable balance and appends the ledger row in the caller's transaction. */
  public void move(Wallet locked, long delta, long heldDelta, LedgerKind kind, UUID walletRequest, UUID actor, Instant now) {
    long balance = locked.balance() + delta, held = locked.held() + heldDelta;
    if (balance < 0 || held < 0) throw new IllegalStateException("Wallet would go negative");
    jdbc.update("UPDATE partner_wallets SET balance=?,held=?,updated_at=? WHERE partner_id=?",
        balance, held, ts(now), locked.partnerId().toString());
    if (delta != 0)
      jdbc.update("INSERT INTO wallet_ledger(id,partner_id,delta,balance_after,kind,wallet_request_id,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
          UUID.randomUUID().toString(), locked.partnerId().toString(), delta, balance, kind.name(),
          walletRequest == null ? null : walletRequest.toString(), actor.toString(), ts(now));
  }

  /** Sale points: lowers the spendable balance of a locked wallet and records the sale reference. */
  public void debitForSale(Wallet locked, long points, UUID sale, UUID actor, Instant now) {
    long balance = locked.balance() - points;
    if (points <= 0 || balance < 0) throw new IllegalStateException("Sale debit checked by the caller");
    jdbc.update("UPDATE partner_wallets SET balance=?,updated_at=? WHERE partner_id=?", balance, ts(now), locked.partnerId().toString());
    jdbc.update("INSERT INTO wallet_ledger(id,partner_id,delta,balance_after,kind,reference_id,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), locked.partnerId().toString(), -points, balance, LedgerKind.SALE_POINTS.name(),
        sale.toString(), actor.toString(), ts(now));
  }

  public void creditForCancelledSale(Wallet locked, long points, UUID sale, UUID actor, Instant now) {
    if (points <= 0) throw new IllegalStateException("Returned points must be positive");
    long balance = locked.balance() + points;
    jdbc.update("UPDATE partner_wallets SET balance=?,updated_at=? WHERE partner_id=?", balance, ts(now), locked.partnerId().toString());
    jdbc.update("INSERT INTO wallet_ledger(id,partner_id,delta,balance_after,kind,reference_id,actor_id,created_at) VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID().toString(), locked.partnerId().toString(), points, balance, LedgerKind.SALE_POINTS_RETURNED.name(),
        sale.toString(), actor.toString(), ts(now));
  }

  /** Spendable balance; 0 before the first movement. */
  public long balance(UUID partner) {
    var rows = jdbc.queryForList("SELECT balance FROM partner_wallets WHERE partner_id=?", Long.class, partner.toString());
    return rows.isEmpty() ? 0 : rows.getFirst();
  }

  /** False when this partner was already alerted that day. */
  public boolean recordLowBalanceAlert(UUID partner, java.time.LocalDate day, long balance) {
    return jdbc.update("INSERT INTO wallet_low_balance_alerts(partner_id,alert_date,balance) VALUES (?,?,?)"
        + " ON CONFLICT DO NOTHING", partner.toString(), java.sql.Date.valueOf(day), balance) == 1;
  }

  public List<LedgerEntry> ledger(UUID partner, int limit) {
    return jdbc.query("SELECT id,delta,balance_after,kind,wallet_request_id,actor_id,created_at FROM wallet_ledger"
            + " WHERE partner_id=? ORDER BY created_at DESC,id DESC LIMIT ?",
        (r, i) -> new LedgerEntry(uuid(r, 1), r.getLong(2), r.getLong(3), LedgerKind.valueOf(r.getString(4)),
            uuid(r, 5), uuid(r, 6), instant(r, 7)),
        partner.toString(), limit);
  }

  // ---- requests ----

  public void insertRequest(WalletRequest q) {
    jdbc.update("INSERT INTO wallet_requests(id,request_id,partner_id,kind,amount,method,reference,note,status,requested_by,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        q.id().toString(), q.requestId().toString(), q.partnerId().toString(), q.kind().name(), q.amount(),
        q.method().name(), q.reference(), q.note(), q.status().name(), q.requestedBy().toString(), ts(q.createdAt()));
  }

  public Optional<WalletRequest> byClientRequest(UUID requestId) {
    return first(jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM wallet_requests WHERE request_id=?", REQUEST, requestId.toString()));
  }

  public Optional<WalletRequest> lockRequest(UUID id) {
    return first(jdbc.query("SELECT " + REQUEST_COLUMNS + " FROM wallet_requests WHERE id=? FOR UPDATE", REQUEST, id.toString()));
  }

  public void decide(UUID id, RequestStatus status, UUID actor, Instant now, String reason, PaymentMethod payout, String payoutReference) {
    if (jdbc.update("UPDATE wallet_requests SET status=?,decided_by=?,decided_at=?,decision_reason=?,payout_method=?,payout_reference=?"
            + " WHERE id=? AND status='PENDING'",
        status.name(), actor.toString(), ts(now), reason, payout == null ? null : payout.name(), payoutReference, id.toString()) != 1)
      throw new IllegalStateException("Request is no longer pending");
  }

  public List<WalletRequest> requests(UUID partner, RequestStatus status, int limit) {
    var sql = new StringBuilder("SELECT " + REQUEST_COLUMNS + " FROM wallet_requests WHERE 1=1");
    var args = new ArrayList<Object>();
    if (partner != null) { sql.append(" AND partner_id=?"); args.add(partner.toString()); }
    if (status != null) { sql.append(" AND status=?"); args.add(status.name()); }
    sql.append(status == RequestStatus.PENDING ? " ORDER BY created_at" : " ORDER BY created_at DESC").append(" LIMIT ?");
    args.add(limit);
    return jdbc.query(sql.toString(), REQUEST, args.toArray());
  }

  // ---- settings ----

  public Settings settings() {
    return jdbc.queryForObject("SELECT min_topup,max_topup,low_balance_threshold FROM wallet_settings WHERE id=1",
        (r, i) -> new Settings(r.getLong(1), r.getLong(2), r.getLong(3)));
  }

  public void updateSettings(Settings s, UUID actor, Instant now) {
    jdbc.update("UPDATE wallet_settings SET min_topup=?,max_topup=?,low_balance_threshold=?,updated_by=?,updated_at=? WHERE id=1",
        s.minTopup(), s.maxTopup(), s.lowBalanceThreshold(), actor.toString(), ts(now));
  }

  // ---- mapping ----

  private static final RowMapper<Wallet> WALLET = (r, i) -> new Wallet(uuid(r, 1), r.getLong(2), r.getLong(3));
  private static final RowMapper<WalletRequest> REQUEST = (r, i) -> new WalletRequest(uuid(r, 1), uuid(r, 2), uuid(r, 3),
      RequestKind.valueOf(r.getString(4)), r.getLong(5), PaymentMethod.valueOf(r.getString(6)), r.getString(7),
      r.getString(8), RequestStatus.valueOf(r.getString(9)), uuid(r, 10), instant(r, 11), uuid(r, 12), instant(r, 13),
      r.getString(14), r.getString(15) == null ? null : PaymentMethod.valueOf(r.getString(15)), r.getString(16));

  private static UUID uuid(ResultSet r, int i) throws SQLException {
    String v = r.getString(i);
    return v == null ? null : UUID.fromString(v.strip());
  }

  private static Instant instant(ResultSet r, int i) throws SQLException {
    var v = r.getTimestamp(i);
    return v == null ? null : v.toInstant();
  }

  private static Timestamp ts(Instant v) {
    return v == null ? null : Timestamp.from(v);
  }

  private static <T> Optional<T> first(List<T> rows) {
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }
}
