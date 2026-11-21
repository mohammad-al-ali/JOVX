package com.jovx.api.security.internal.accounts;

import com.jovx.api.security.api.PartnerAccountProvisioning;
import com.jovx.api.security.internal.sessions.AuthFailure;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Profile("local-postgres")
final class PartnerProvisioning implements PartnerAccountProvisioning {
    private final JdbcAccountStore store;
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final SixDigitPinEncoder encoder;
    private final Clock clock;
    PartnerProvisioning(JdbcAccountStore store, DataSource source, SixDigitPinEncoder encoder, Clock clock) {
        this.store = store; this.source = source; this.encoder = encoder; this.clock = clock; this.jdbc = new JdbcTemplate(source);
    }
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Partner provisioning requires its owner's transaction");
    }
    public UUID createPending(String name, String phone, String pin) {
        requireTransaction();
        UUID id = UUID.randomUUID(); store.createPendingPartner(id, name, phone, pin); return id;
    }
    public void activate(UUID id) {
        requireTransaction();
        var rows = jdbc.queryForList("SELECT u.status FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND r.\"key\"='PARTNER' FOR UPDATE", String.class, id.toString());
        if (rows.isEmpty() || !java.util.Set.of("ACTIVE","PENDING").contains(rows.getFirst()))
            throw new IllegalStateException("Partner account cannot be activated");
        jdbc.update("UPDATE users SET status='ACTIVE' WHERE id=?", id.toString());
    }

    public void resetPin(UUID id, String pin) {
        requireTransaction();
        SixDigitPinEncoder.validateForCreation(pin);
        var rows = jdbc.queryForList("SELECT u.status FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND r.\"key\"='PARTNER' FOR UPDATE", String.class, id.toString());
        if (rows.isEmpty()) throw new AuthFailure(409, "TARGET_MUST_BE_PARTNER");
        var now = Timestamp.from(clock.instant());
        jdbc.update("UPDATE users SET pin_hash=?,pin_changed_at=?,failed_pin_attempts=0,locked_until=NULL WHERE id=?",
            encoder.encode(pin), now, id.toString());
        jdbc.update("UPDATE identity_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", now, id.toString());
    }
}
