package com.jovx.api.security.internal.accounts;

import com.jovx.api.security.api.PartnerAccountProvisioning;
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
    PartnerProvisioning(JdbcAccountStore store, DataSource source) {
        this.store = store; this.source = source; this.jdbc = new JdbcTemplate(source);
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
}
