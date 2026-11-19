package com.jovx.api.points.repository;

import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local-postgres")
public final class PointsRepository {
  private final JdbcTemplate jdbc;

  public PointsRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  /** A member without any points movement has no row yet: balance 0. */
  public long balance(UUID member) {
    var rows = jdbc.queryForList("SELECT balance FROM points_accounts WHERE member_account_id=?", Long.class, member.toString());
    return rows.isEmpty() ? 0 : rows.getFirst();
  }

  public List<Entry> history(UUID member, int offset, int limit) {
    return jdbc.query(
        "SELECT id,delta,balance_after,kind,reference_id,created_at FROM points_ledger WHERE member_account_id=?"
            + " ORDER BY created_at DESC,id DESC OFFSET ? LIMIT ?",
        (r, i) -> new Entry(UUID.fromString(r.getString(1)), r.getLong(2), r.getLong(3), r.getString(4),
            r.getString(5) == null ? null : UUID.fromString(r.getString(5)), r.getTimestamp(6).toInstant()),
        member.toString(), Math.max(0, offset), Math.max(1, Math.min(limit, 100)));
  }

  public record Entry(UUID id, long delta, long balanceAfter, String kind, UUID referenceId, Instant createdAt) {}
}
