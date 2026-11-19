package com.jovx.api.membership.repository;

import com.jovx.api.membership.model.MembershipModels.*;
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

/** Membership-owned tables only. Account facts come through security::api. */
@Repository
@Profile("local-postgres")
public final class MembershipRepository {
  private static final String PLAN_COLUMNS =
      "id,name_ar,price_amount,currency,duration_months,family_slots,active";
  private static final String MEMBER_COLUMNS =
      "account_id,member_number,plan_id,starts_at,expires_at";
  private static final String CARD_COLUMNS =
      "id,code,batch_label,status,member_account_id,registered_at,assigned_at,suspended_at";
  private static final String PAYMENT_COLUMNS =
      "id,request_id,member_account_id,plan_id,kind,amount,currency,method,reference,"
          + "previous_expires_at,new_expires_at,recorded_by,created_at";
  private final JdbcTemplate jdbc;

  public MembershipRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  // ---- plans ----

  public List<Plan> plans(boolean activeOnly) {
    return jdbc.query(
        "SELECT " + PLAN_COLUMNS + " FROM membership_plans"
            + (activeOnly ? " WHERE active" : "") + " ORDER BY active DESC, price_amount, name_ar",
        PLAN);
  }

  public Optional<Plan> plan(UUID id) {
    return first(jdbc.query("SELECT " + PLAN_COLUMNS + " FROM membership_plans WHERE id=?", PLAN, id.toString()));
  }

  public void insertPlan(Plan plan, Instant now) {
    jdbc.update(
        "INSERT INTO membership_plans(id,name_ar,price_amount,currency,duration_months,family_slots,active,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        plan.id().toString(), plan.name(), plan.priceAmount(), plan.currency(), plan.durationMonths(),
        plan.familySlots(), plan.active(), ts(now));
  }

  public boolean updatePlan(Plan plan) {
    return jdbc.update(
            "UPDATE membership_plans SET name_ar=?,price_amount=?,currency=?,duration_months=?,family_slots=?,active=?"
                + " WHERE id=?",
            plan.name(), plan.priceAmount(), plan.currency(), plan.durationMonths(), plan.familySlots(),
            plan.active(), plan.id().toString())
        == 1;
  }

  // ---- cards ----

  /** Inserts a printed card unless the code is already registered. */
  public boolean insertCard(String code, String batch, UUID registrar, Instant now) {
    return jdbc.update(
            "INSERT INTO member_cards(id,code,batch_label,status,registered_by,registered_at)"
                + " VALUES (?,?,?,'IN_STOCK',?,?) ON CONFLICT (code) DO NOTHING",
            UUID.randomUUID().toString(), code, batch, registrar.toString(), ts(now))
        == 1;
  }

  public List<Card> cards(CardStatus status, String codeFragment, int limit) {
    var sql = new StringBuilder("SELECT " + CARD_COLUMNS + " FROM member_cards WHERE 1=1");
    var args = new ArrayList<Object>();
    if (status != null) { sql.append(" AND status=?"); args.add(status.name()); }
    if (codeFragment != null && !codeFragment.isBlank()) {
      sql.append(" AND (code ILIKE ? ESCAPE '\\' OR batch_label ILIKE ? ESCAPE '\\')");
      String like = "%" + codeFragment.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
      args.add(like); args.add(like);
    }
    sql.append(" ORDER BY registered_at DESC, code LIMIT ?");
    args.add(Math.max(1, Math.min(limit, 200)));
    return jdbc.query(sql.toString(), CARD, args.toArray());
  }

  public Map<CardStatus, Long> cardCounts() {
    var result = new EnumMap<CardStatus, Long>(CardStatus.class);
    for (var s : CardStatus.values()) result.put(s, 0L);
    jdbc.query("SELECT status,COUNT(*) FROM member_cards GROUP BY status",
        (org.springframework.jdbc.core.RowCallbackHandler) r -> result.put(CardStatus.valueOf(r.getString(1)), r.getLong(2)));
    return result;
  }

  public Optional<Card> cardByCodeForUpdate(String code) {
    return first(jdbc.query("SELECT " + CARD_COLUMNS + " FROM member_cards WHERE code=? FOR UPDATE", CARD, code));
  }

  public Optional<Card> cardOf(UUID member) {
    return first(jdbc.query("SELECT " + CARD_COLUMNS + " FROM member_cards WHERE member_account_id=?", CARD, member.toString()));
  }

  public Map<UUID, Card> cardsOf(Collection<UUID> members) {
    var result = new HashMap<UUID, Card>();
    if (members.isEmpty()) return result;
    var keys = members.stream().map(UUID::toString).toList();
    String marks = String.join(",", Collections.nCopies(keys.size(), "?"));
    for (var c : jdbc.query("SELECT " + CARD_COLUMNS + " FROM member_cards WHERE member_account_id IN (" + marks + ")", CARD, keys.toArray()))
      result.put(c.member(), c);
    return result;
  }

  public void assignCard(UUID card, UUID member, Instant now) {
    if (jdbc.update("UPDATE member_cards SET status='ASSIGNED',member_account_id=?,assigned_at=? WHERE id=? AND status='IN_STOCK'",
            member.toString(), ts(now), card.toString()) != 1)
      throw new IllegalStateException("Card was not in stock");
  }

  public boolean suspendCard(UUID member, Instant now) {
    return jdbc.update("UPDATE member_cards SET status='SUSPENDED',suspended_at=? WHERE member_account_id=? AND status='ASSIGNED'",
            ts(now), member.toString()) == 1;
  }

  // ---- members ----

  public String nextMemberNumberValue() {
    return Long.toString(jdbc.queryForObject("SELECT nextval('member_number_seq')", Long.class));
  }

  public void insertMember(Member m, UUID seller, Instant now) {
    jdbc.update("INSERT INTO members(account_id,member_number,plan_id,starts_at,expires_at,sold_by,created_at) VALUES (?,?,?,?,?,?,?)",
        m.accountId().toString(), m.memberNumber(), m.planId().toString(), ts(m.startsAt()), ts(m.expiresAt()),
        seller.toString(), ts(now));
  }

  public Optional<Member> member(UUID account) {
    return first(jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM members WHERE account_id=?", MEMBER, account.toString()));
  }

  public Optional<Member> memberForUpdate(UUID account) {
    return first(jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM members WHERE account_id=? FOR UPDATE", MEMBER, account.toString()));
  }

  public boolean isMember(UUID account) {
    return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM members WHERE account_id=?)", Boolean.class, account.toString()));
  }

  public List<Member> membersByNumber(String fragment, int limit) {
    return jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM members WHERE member_number ILIKE ? ORDER BY created_at DESC LIMIT ?",
        MEMBER, "%" + fragment.replaceAll("[^A-Za-z0-9-]", "") + "%", limit);
  }

  public List<Member> members(Collection<UUID> accounts) {
    if (accounts.isEmpty()) return List.of();
    var keys = accounts.stream().map(UUID::toString).distinct().toList();
    String marks = String.join(",", Collections.nCopies(keys.size(), "?"));
    return jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM members WHERE account_id IN (" + marks + ")", MEMBER, keys.toArray());
  }

  public List<Member> recentMembers(int limit) {
    return jdbc.query("SELECT " + MEMBER_COLUMNS + " FROM members ORDER BY created_at DESC LIMIT ?", MEMBER, limit);
  }

  public void updateTerm(UUID member, UUID plan, Instant expiresAt) {
    jdbc.update("UPDATE members SET plan_id=?,expires_at=? WHERE account_id=?", plan.toString(), ts(expiresAt), member.toString());
  }

  // ---- payments and audit ----

  public void insertPayment(Payment p) {
    jdbc.update("INSERT INTO membership_payments(" + PAYMENT_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        p.id().toString(), p.requestId().toString(), p.member().toString(), p.planId().toString(), p.kind().name(),
        p.amount(), p.currency(), p.method().name(), p.reference(), ts(p.previousExpiresAt()), ts(p.newExpiresAt()),
        p.recordedBy().toString(), ts(p.createdAt()));
  }

  public Optional<Payment> paymentByRequest(UUID request) {
    return first(jdbc.query("SELECT " + PAYMENT_COLUMNS + " FROM membership_payments WHERE request_id=?", PAYMENT, request.toString()));
  }

  public List<Payment> payments(UUID member) {
    return jdbc.query("SELECT " + PAYMENT_COLUMNS + " FROM membership_payments WHERE member_account_id=? ORDER BY created_at DESC",
        PAYMENT, member.toString());
  }

  public void event(UUID member, UUID actor, String action, Instant now) {
    jdbc.update("INSERT INTO membership_events(id,member_account_id,actor_id,action,created_at) VALUES (?,?,?,?,?)",
        UUID.randomUUID().toString(), member.toString(), actor.toString(), action, ts(now));
  }

  // ---- scan tokens ----

  public void replaceScanToken(String digest, UUID member, UUID card, Instant now, Instant expires) {
    jdbc.update("DELETE FROM member_scan_tokens WHERE member_account_id=? AND consumed_at IS NULL", member.toString());
    jdbc.update("INSERT INTO member_scan_tokens(token_digest,member_account_id,card_id,issued_at,expires_at) VALUES (?,?,?,?,?)",
        digest, member.toString(), card.toString(), ts(now), ts(expires));
  }

  public void dropScanTokens(UUID member) {
    jdbc.update("DELETE FROM member_scan_tokens WHERE member_account_id=? AND consumed_at IS NULL", member.toString());
  }

  // ---- mapping ----

  private static final RowMapper<Plan> PLAN = (r, i) -> new Plan(uuid(r, 1), r.getString(2), r.getLong(3),
      r.getString(4), r.getInt(5), r.getInt(6), r.getBoolean(7));
  private static final RowMapper<Member> MEMBER = (r, i) -> new Member(uuid(r, 1), r.getString(2), uuid(r, 3),
      instant(r, 4), instant(r, 5));
  private static final RowMapper<Card> CARD = (r, i) -> new Card(uuid(r, 1), r.getString(2), r.getString(3),
      CardStatus.valueOf(r.getString(4)), uuid(r, 5), instant(r, 6), instant(r, 7), instant(r, 8));
  private static final RowMapper<Payment> PAYMENT = (r, i) -> new Payment(uuid(r, 1), uuid(r, 2), uuid(r, 3),
      uuid(r, 4), PaymentKind.valueOf(r.getString(5)), r.getLong(6), r.getString(7),
      PaymentMethod.valueOf(r.getString(8)), r.getString(9), instant(r, 10), instant(r, 11), uuid(r, 12), instant(r, 13));

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
