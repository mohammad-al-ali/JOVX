package com.jovx.api.security.internal.sessions;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class AttemptLimiter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final AuthSettings settings;
    public AttemptLimiter(DataSource source, Clock clock, AuthSettings settings) {
        jdbc = new JdbcTemplate(source); tx = new TransactionTemplate(new JdbcTransactionManager(source));
        this.clock = clock; this.settings = settings;
    }
    public void consume(String operation, String address) {
        boolean login = "login".equals(operation) || "claim".equals(operation);
        int limit = login ? settings.loginIpLimit() : settings.tokenIpLimit();
        Duration window = login ? settings.loginIpWindow() : settings.tokenIpWindow();
        String bucket = TokenSupport.digest(operation + ":" + address);
        // Bounded cleanup is separate from the locked per-address bucket transaction.
        jdbc.update("DELETE FROM identity_auth_rate_limits WHERE bucket_key IN (SELECT bucket_key FROM identity_auth_rate_limits WHERE expires_at < ? ORDER BY expires_at LIMIT 100)",
            Timestamp.from(clock.instant().minus(Duration.ofDays(1))));
        long retry = tx.execute(status -> {
            var now = clock.instant();
            jdbc.update("INSERT INTO identity_auth_rate_limits (bucket_key,expires_at,attempts) VALUES (?,?,0) ON CONFLICT (bucket_key) DO NOTHING",
                bucket, Timestamp.from(now.plus(window)));
            var state = jdbc.queryForObject("SELECT expires_at,attempts FROM identity_auth_rate_limits WHERE bucket_key=? FOR UPDATE",
                (row,index) -> new Bucket(row.getTimestamp(1).toInstant(),row.getInt(2)), bucket);
            var expires = state.expires();
            int count = state.attempts();
            if (!expires.isAfter(now)) {
                jdbc.update("UPDATE identity_auth_rate_limits SET expires_at=?,attempts=1 WHERE bucket_key=?", Timestamp.from(now.plus(window)), bucket);
                return 0L;
            }
            if (count >= limit) return Math.max(1L, Duration.between(now, expires).toSeconds() + 1);
            jdbc.update("UPDATE identity_auth_rate_limits SET attempts=attempts+1 WHERE bucket_key=?", bucket);
            return 0L;
        });
        if (retry > 0) throw new AuthFailure(429, "RATE_LIMITED", retry);
    }
    private record Bucket(java.time.Instant expires,int attempts) {}
}
