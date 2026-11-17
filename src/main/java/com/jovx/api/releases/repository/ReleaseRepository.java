package com.jovx.api.releases.repository;

import com.jovx.api.releases.model.ReleaseModels.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@Profile("local-postgres")
public class ReleaseRepository {
  private static final String COLUMNS =
      "id,app,platform,version_name,build_number,download_url,sha256,size_bytes,notes_ar,"
          + "source_commit,status,update_mode,required_after,created_at,published_at";
  private final JdbcTemplate jdbc;

  public ReleaseRepository(DataSource source) {
    jdbc = new JdbcTemplate(source);
  }

  public List<AppRelease> releases(AppKind app, Platform platform) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM app_releases WHERE app=? AND platform=? ORDER BY build_number DESC",
        (r, i) -> map(r),
        app.name(),
        platform.name());
  }

  public List<AppRelease> published(AppKind app, Platform platform) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM app_releases WHERE app=? AND platform=? AND status='PUBLISHED'",
        (r, i) -> map(r),
        app.name(),
        platform.name());
  }

  public Optional<AppRelease> lock(UUID id) {
    return jdbc.query("SELECT " + COLUMNS + " FROM app_releases WHERE id=? FOR UPDATE", (r, i) -> map(r), id.toString())
        .stream()
        .findFirst();
  }

  public Optional<AppRelease> byBuild(AppKind app, Platform platform, int build) {
    return jdbc.query(
            "SELECT " + COLUMNS + " FROM app_releases WHERE app=? AND platform=? AND build_number=?",
            (r, i) -> map(r),
            app.name(),
            platform.name(),
            build)
        .stream()
        .findFirst();
  }

  /** Inserts a DRAFT; a concurrent import of the same build keeps the first row. */
  public void insertDraft(UUID id, ReleaseManifest m, UUID actor, Instant now) {
    jdbc.update(
        "INSERT INTO app_releases(id,app,platform,version_name,build_number,download_url,sha256,"
            + "size_bytes,notes_ar,source_commit,status,update_mode,created_at,created_by)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,'DRAFT','OPTIONAL',?,?) ON CONFLICT DO NOTHING",
        id.toString(),
        m.app().name(),
        m.platform().name(),
        m.versionName(),
        m.buildNumber(),
        m.downloadUrl(),
        m.sha256(),
        m.sizeBytes(),
        m.notes(),
        m.sourceCommit(),
        Timestamp.from(now),
        actor.toString());
  }

  public void updatePolicy(UUID id, UpdateMode mode, Instant requiredAfter) {
    jdbc.update(
        "UPDATE app_releases SET update_mode=?,required_after=? WHERE id=?",
        mode.name(),
        requiredAfter == null ? null : Timestamp.from(requiredAfter),
        id.toString());
  }

  public void setStatus(UUID id, ReleaseStatus status, Instant now) {
    jdbc.update(
        "UPDATE app_releases SET status=?,published_at=CASE WHEN ?='PUBLISHED' THEN ? ELSE published_at END WHERE id=?",
        status.name(),
        status.name(),
        Timestamp.from(now),
        id.toString());
  }

  /** Locks the settings row so policy changes are serialized per app. */
  public int lockMinSupported(AppKind app, Platform platform) {
    return jdbc.queryForObject(
        "SELECT min_supported_build FROM app_update_settings WHERE app=? AND platform=? FOR UPDATE",
        Integer.class,
        app.name(),
        platform.name());
  }

  public int minSupported(AppKind app, Platform platform) {
    return jdbc.queryForObject(
        "SELECT min_supported_build FROM app_update_settings WHERE app=? AND platform=?",
        Integer.class,
        app.name(),
        platform.name());
  }

  public void setMinSupported(AppKind app, Platform platform, int build, UUID actor, Instant now) {
    jdbc.update(
        "UPDATE app_update_settings SET min_supported_build=?,updated_at=?,updated_by=? WHERE app=? AND platform=?",
        build,
        Timestamp.from(now),
        actor.toString(),
        app.name(),
        platform.name());
  }

  public void audit(UUID release, AppKind app, UUID actor, String action, Instant now) {
    jdbc.update(
        "INSERT INTO app_release_events(id,release_id,app,actor_id,action,created_at) VALUES (?,?,?,?,?,?)",
        UUID.randomUUID().toString(),
        release == null ? null : release.toString(),
        app.name(),
        actor.toString(),
        action,
        Timestamp.from(now));
  }

  private static AppRelease map(ResultSet r) throws SQLException {
    return new AppRelease(
        UUID.fromString(r.getString("id")),
        AppKind.valueOf(r.getString("app")),
        Platform.valueOf(r.getString("platform")),
        r.getString("version_name"),
        r.getInt("build_number"),
        r.getString("download_url"),
        r.getString("sha256"),
        r.getLong("size_bytes"),
        r.getString("notes_ar"),
        r.getString("source_commit"),
        ReleaseStatus.valueOf(r.getString("status")),
        UpdateMode.valueOf(r.getString("update_mode")),
        instant(r, "required_after"),
        instant(r, "created_at"),
        instant(r, "published_at"));
  }

  private static Instant instant(ResultSet r, String column) throws SQLException {
    var value = r.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
