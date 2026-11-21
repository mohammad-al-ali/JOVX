package com.jovx.api.sales.repository;

import com.jovx.api.sales.model.SalesModels.*;
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
public final class SalesRepository {
  private static final String SCAN_COLUMNS =
      "id,partner_id,store_id,member_account_id,card_id,method,scanned_by,created_at,expires_at,consumed_at";
  private static final String SALE_COLUMNS =
      "id,request_id,request_digest,partner_id,store_id,member_account_id,card_id,scan_id,kind,currency,"
          + "gross_amount,discount_amount,net_amount,discount_percent,points,seller_id,created_at";
  private final JdbcTemplate jdbc;

  public SalesRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  // ---- scans ----

  public void insertScan(Scan s) {
    jdbc.update("INSERT INTO sale_scans(" + SCAN_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
        s.id().toString(), s.partnerId().toString(), s.storeId().toString(), s.memberAccount().toString(),
        s.cardId().toString(), s.method().name(), s.scannedBy().toString(), ts(s.createdAt()), ts(s.expiresAt()), null);
  }

  public Optional<Scan> lockScan(UUID id) {
    return first(jdbc.query("SELECT " + SCAN_COLUMNS + " FROM sale_scans WHERE id=? FOR UPDATE", SCAN, id.toString()));
  }

  public void consumeScan(UUID id, Instant now) {
    jdbc.update("UPDATE sale_scans SET consumed_at=? WHERE id=?", ts(now), id.toString());
  }

  // ---- sales ----

  /** Serializes retries of one requestId, so two parallel submits cannot both sell. */
  public void lockRequest(UUID requestId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", requestId.toString());
  }

  public Optional<Sale> byRequest(UUID requestId) {
    return first(jdbc.query("SELECT " + SALE_COLUMNS + " FROM sales WHERE request_id=?", SALE, requestId.toString()));
  }

  public Optional<Sale> sale(UUID id) {
    return first(jdbc.query("SELECT " + SALE_COLUMNS + " FROM sales WHERE id=?", SALE, id.toString()));
  }

  public void insertSale(Sale s, List<SaleLine> lines) {
    jdbc.update("INSERT INTO sales(" + SALE_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        s.id().toString(), s.requestId().toString(), s.requestDigest(), s.partnerId().toString(), s.storeId().toString(),
        s.memberAccount().toString(), s.cardId().toString(), s.scanId().toString(), s.kind().name(), s.currency(),
        s.grossAmount(), s.discountAmount(), s.netAmount(), s.discountPercent(), s.points(), s.sellerId().toString(),
        ts(s.createdAt()));
    for (var l : lines)
      jdbc.update("INSERT INTO sale_lines(sale_id,line_no,product_id,name,unit_price,unit_member_price,unit_points,quantity)"
              + " VALUES (?,?,?,?,?,?,?,?)",
          s.id().toString(), l.lineNo(), l.productId().toString(), l.name(), l.unitPrice(), l.unitMemberPrice(),
          l.unitPoints(), l.quantity());
  }

  public List<SaleLine> lines(UUID sale) {
    return jdbc.query("SELECT line_no,product_id,name,unit_price,unit_member_price,unit_points,quantity FROM sale_lines"
        + " WHERE sale_id=? ORDER BY line_no", LINE, sale.toString());
  }

  public Map<UUID, List<SaleLine>> lines(Collection<UUID> sales) {
    var result = new HashMap<UUID, List<SaleLine>>();
    if (sales.isEmpty()) return result;
    var keys = sales.stream().map(UUID::toString).toList();
    String marks = String.join(",", Collections.nCopies(keys.size(), "?"));
    jdbc.query("SELECT sale_id,line_no,product_id,name,unit_price,unit_member_price,unit_points,quantity FROM sale_lines"
            + " WHERE sale_id IN (" + marks + ") ORDER BY sale_id,line_no",
        (org.springframework.jdbc.core.RowCallbackHandler) r -> result
            .computeIfAbsent(UUID.fromString(r.getString(1)), k -> new ArrayList<>())
            .add(new SaleLine(r.getInt(2), UUID.fromString(r.getString(3)), r.getString(4), r.getLong(5),
                r.getLong(6), r.getLong(7), r.getInt(8))),
        keys.toArray());
    return result;
  }

  // ---- reading ----

  public List<Sale> sales(Scope scope, Instant since, int offset, int limit) {
    var args = new ArrayList<Object>();
    String where = where(scope, since, args, "");
    args.add(Math.max(0, offset));
    args.add(Math.max(1, Math.min(limit, 100)));
    return jdbc.query("SELECT " + SALE_COLUMNS + " FROM sales WHERE " + where
        + " ORDER BY created_at DESC,id DESC OFFSET ? LIMIT ?", SALE, args.toArray());
  }

  public Totals totals(Scope scope, Instant since) {
    var args = new ArrayList<Object>();
    String where = where(scope, since, args, "");
    return jdbc.queryForObject("SELECT COUNT(*),COUNT(DISTINCT member_account_id),COALESCE(SUM(gross_amount),0),"
            + "COALESCE(SUM(discount_amount),0),COALESCE(SUM(net_amount),0),COALESCE(SUM(points),0) FROM sales WHERE " + where,
        (r, i) -> new Totals(r.getLong(1), r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5), r.getLong(6)),
        args.toArray());
  }

  public List<TopProduct> topProducts(Scope scope, Instant since, int limit) {
    var args = new ArrayList<Object>();
    String where = where(scope, since, args, "s.");
    args.add(limit);
    return jdbc.query("SELECT l.product_id,MAX(l.name),SUM(l.quantity),SUM(l.unit_member_price*l.quantity)"
            + " FROM sale_lines l JOIN sales s ON s.id=l.sale_id WHERE " + where
            + " GROUP BY l.product_id ORDER BY SUM(l.quantity) DESC,MAX(l.name) LIMIT ?",
        (r, i) -> new TopProduct(UUID.fromString(r.getString(1)), r.getString(2), r.getLong(3), r.getLong(4)),
        args.toArray());
  }

  public List<Sale> memberSales(UUID member, int offset, int limit) {
    return jdbc.query("SELECT " + SALE_COLUMNS + " FROM sales WHERE member_account_id=?"
            + " ORDER BY created_at DESC,id DESC OFFSET ? LIMIT ?",
        SALE, member.toString(), Math.max(0, offset), Math.max(1, Math.min(limit, 100)));
  }

  public long[] memberTotals(UUID member) {
    return jdbc.queryForObject("SELECT COUNT(*),COALESCE(SUM(discount_amount),0),COALESCE(SUM(points),0) FROM sales"
        + " WHERE member_account_id=?", (r, i) -> new long[] {r.getLong(1), r.getLong(2), r.getLong(3)}, member.toString());
  }

  /** Parameters are appended to {@code args}; store ids only come from server-side scope, never raw text. */
  private static String where(Scope scope, Instant since, List<Object> args, String p) {
    var sql = new StringBuilder(p + "partner_id=?");
    args.add(scope.partnerId().toString());
    if (since != null) { sql.append(" AND " + p + "created_at>=?"); args.add(ts(since)); }
    if (scope.seller() != null) { sql.append(" AND " + p + "seller_id=?"); args.add(scope.seller().toString()); }
    if (scope.stores() != null) {
      if (scope.stores().isEmpty()) return sql.append(" AND FALSE").toString();
      sql.append(" AND " + p + "store_id IN (").append(String.join(",", Collections.nCopies(scope.stores().size(), "?"))).append(")");
      scope.stores().forEach(s -> args.add(s.toString()));
    }
    return sql.toString();
  }

  // ---- mapping ----

  private static final RowMapper<Scan> SCAN = (r, i) -> new Scan(uuid(r, 1), uuid(r, 2), uuid(r, 3), uuid(r, 4),
      uuid(r, 5), ScanMethod.valueOf(r.getString(6)), uuid(r, 7), instant(r, 8), instant(r, 9), instant(r, 10));
  private static final RowMapper<Sale> SALE = (r, i) -> new Sale(uuid(r, 1), uuid(r, 2), r.getString(3), uuid(r, 4),
      uuid(r, 5), uuid(r, 6), uuid(r, 7), uuid(r, 8), SaleKind.valueOf(r.getString(9)), r.getString(10), r.getLong(11),
      r.getLong(12), r.getLong(13), r.getBigDecimal(14), r.getLong(15), uuid(r, 16), instant(r, 17));
  private static final RowMapper<SaleLine> LINE = (r, i) -> new SaleLine(r.getInt(1), uuid(r, 2), r.getString(3),
      r.getLong(4), r.getLong(5), r.getLong(6), r.getInt(7));

  private static UUID uuid(ResultSet r, int column) throws SQLException {
    String v = r.getString(column);
    return v == null ? null : UUID.fromString(v.trim());
  }

  private static Instant instant(ResultSet r, int column) throws SQLException {
    Timestamp v = r.getTimestamp(column);
    return v == null ? null : v.toInstant();
  }

  private static Timestamp ts(Instant v) {
    return v == null ? null : Timestamp.from(v);
  }

  private static <T> Optional<T> first(List<T> rows) {
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }
}
