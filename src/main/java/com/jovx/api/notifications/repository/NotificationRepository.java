package com.jovx.api.notifications.repository;

import com.jovx.api.notifications.model.NotificationModels.*;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Module-owned tables: device_tokens, notifications, notification_outbox, settings, admin_messages. */
@Repository
@Profile("local-postgres")
public final class NotificationRepository {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final TypeReference<Map<String, String>> MAP = new TypeReference<>() {};
  private final JdbcTemplate jdbc;

  public NotificationRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  // ---- settings ----

  public Optional<EventSetting> setting(String kind) {
    return first(jdbc.query("SELECT kind,audience,enabled FROM notification_event_settings WHERE kind=?", SETTING, kind));
  }

  public List<EventSetting> settings() {
    return jdbc.query("SELECT kind,audience,enabled FROM notification_event_settings ORDER BY audience,kind", SETTING);
  }

  public boolean updateSetting(String kind, boolean enabled, UUID actor, Instant now) {
    return jdbc.update("UPDATE notification_event_settings SET enabled=?,updated_by=?,updated_at=? WHERE kind=?",
        enabled, actor.toString(), ts(now), kind) == 1;
  }

  // ---- inbox and outbox ----

  public void insert(Notification n) {
    jdbc.update("INSERT INTO notifications(id,account_id,kind,title,body,data,created_at) VALUES (?,?,?,?,?,?::jsonb,?)",
        n.id().toString(), n.accountId().toString(), n.kind(), n.title(), n.body(), JSON.writeValueAsString(n.data()),
        ts(n.createdAt()));
    jdbc.update("INSERT INTO notification_outbox(notification_id,status,attempts,next_attempt_at,updated_at)"
        + " VALUES (?,'PENDING',0,?,?)", n.id().toString(), ts(n.createdAt()), ts(n.createdAt()));
  }

  public List<Notification> inbox(UUID account, int offset, int limit) {
    return jdbc.query("SELECT id,account_id,kind,title,body,data::text,created_at,read_at FROM notifications"
            + " WHERE account_id=? ORDER BY created_at DESC,id DESC OFFSET ? LIMIT ?",
        NOTIFICATION, account.toString(), Math.max(0, offset), Math.max(1, Math.min(limit, 100)));
  }

  public long unread(UUID account) {
    return jdbc.queryForObject("SELECT COUNT(*) FROM notifications WHERE account_id=? AND read_at IS NULL",
        Long.class, account.toString());
  }

  public boolean markRead(UUID account, UUID id, Instant now) {
    return jdbc.update("UPDATE notifications SET read_at=COALESCE(read_at,?) WHERE id=? AND account_id=?",
        ts(now), id.toString(), account.toString()) == 1;
  }

  public void markAllRead(UUID account, Instant now) {
    jdbc.update("UPDATE notifications SET read_at=? WHERE account_id=? AND read_at IS NULL", ts(now), account.toString());
  }

  /** Due rows, locked so parallel dispatchers never send the same notification twice. */
  public List<Pending> lockDue(Instant now, int limit) {
    return jdbc.query("SELECT o.notification_id,n.account_id,n.title,n.body,n.data::text,o.attempts"
            + " FROM notification_outbox o JOIN notifications n ON n.id=o.notification_id"
            + " WHERE o.status='PENDING' AND o.next_attempt_at<=? ORDER BY o.next_attempt_at LIMIT ?"
            + " FOR UPDATE OF o SKIP LOCKED",
        (r, i) -> new Pending(uuid(r, 1), uuid(r, 2), r.getString(3), r.getString(4), map(r.getString(5)), r.getInt(6)),
        ts(now), limit);
  }

  public void finish(UUID notification, OutboxStatus status, int attempts, String error, Instant now) {
    jdbc.update("UPDATE notification_outbox SET status=?,attempts=?,last_error=?,updated_at=? WHERE notification_id=?",
        status.name(), attempts, error, ts(now), notification.toString());
  }

  public void retryLater(UUID notification, int attempts, Instant next, String error, Instant now) {
    jdbc.update("UPDATE notification_outbox SET attempts=?,next_attempt_at=?,last_error=?,updated_at=? WHERE notification_id=?",
        attempts, ts(next), error, ts(now), notification.toString());
  }

  // ---- devices ----

  public void saveToken(UUID account, String token, Platform platform, Instant now) {
    jdbc.update("INSERT INTO device_tokens(token,account_id,platform,updated_at) VALUES (?,?,?,?)"
            + " ON CONFLICT (token) DO UPDATE SET account_id=EXCLUDED.account_id,platform=EXCLUDED.platform,"
            + "updated_at=EXCLUDED.updated_at",
        token, account.toString(), platform.name(), ts(now));
  }

  public void deleteToken(UUID account, String token) {
    jdbc.update("DELETE FROM device_tokens WHERE token=? AND account_id=?", token, account.toString());
  }

  public void forgetToken(String token) {
    jdbc.update("DELETE FROM device_tokens WHERE token=?", token);
  }

  public List<String> tokens(UUID account) {
    return jdbc.queryForList("SELECT token FROM device_tokens WHERE account_id=? ORDER BY updated_at DESC LIMIT 10",
        String.class, account.toString());
  }

  // ---- administration messages ----

  public Optional<Integer> messageRecipients(UUID requestId) {
    return first(jdbc.queryForList("SELECT recipients FROM admin_messages WHERE request_id=?", Integer.class,
        requestId.toString()));
  }

  public void insertMessage(UUID id, UUID requestId, UUID partner, String title, String body, int recipients,
      UUID actor, Instant now) {
    jdbc.update("INSERT INTO admin_messages(id,request_id,partner_id,title,body,recipients,sent_by,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id.toString(), requestId.toString(), partner == null ? null : partner.toString(), title, body, recipients,
        actor.toString(), ts(now));
  }

  /** Serializes retries of one message request. */
  public void lockRequest(UUID requestId) {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", requestId.toString());
  }

  // ---- mapping ----

  private static final RowMapper<EventSetting> SETTING = (r, i) ->
      new EventSetting(r.getString(1), Audience.valueOf(r.getString(2)), r.getBoolean(3));
  private static final RowMapper<Notification> NOTIFICATION = (r, i) -> new Notification(uuid(r, 1), uuid(r, 2),
      r.getString(3), r.getString(4), r.getString(5), map(r.getString(6)), instant(r, 7), instant(r, 8));

  private static Map<String, String> map(String json) {
    return json == null || json.isBlank() ? Map.of() : JSON.readValue(json, MAP);
  }

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
