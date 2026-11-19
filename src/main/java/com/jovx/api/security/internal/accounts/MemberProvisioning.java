package com.jovx.api.security.internal.accounts;

import com.jovx.api.security.api.MemberAccounts;
import com.jovx.api.security.internal.sessions.AuthFailure;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@Profile("local-postgres")
final class MemberProvisioning implements MemberAccounts {
    /** Never a valid encoder format, so an unclaimed account cannot authenticate. */
    static final String UNSET_PREFIX = "{unset}";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final DataSource source;
    private final SixDigitPinEncoder encoder;
    private final Clock clock;

    MemberProvisioning(DataSource source, SixDigitPinEncoder encoder, Clock clock) {
        this.source = source; this.encoder = encoder; this.clock = clock; jdbc = new JdbcTemplate(source);
    }

    private void transaction() {
        if (!TransactionSynchronizationManager.hasResource(source))
            throw new IllegalStateException("Member account command requires its owner's transaction");
    }

    public UUID createUnclaimed(String name, String inputPhone) {
        transaction();
        if (name == null || name.isBlank() || name.strip().length() > 200) throw new IllegalArgumentException("Name is required");
        String phone = InternationalPhone.normalize(inputPhone);
        byte[] filler = new byte[24]; RANDOM.nextBytes(filler);
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO users (id,name,phone_e164,role_id,status,pin_hash) SELECT ?,?,?,id,'PENDING',? FROM roles WHERE \"key\"='MEMBER'",
                id.toString(), name.strip(), phone, UNSET_PREFIX + HexFormat.of().formatHex(filler));
        } catch (DuplicateKeyException ex) {
            throw new PhoneAlreadyRegistered();
        }
        return id;
    }

    public boolean unclaimed(UUID account) {
        var rows = jdbc.queryForList("SELECT u.pin_hash FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND r.\"key\"='MEMBER' AND u.status='PENDING'",
            String.class, account.toString());
        return !rows.isEmpty() && rows.getFirst().startsWith(UNSET_PREFIX);
    }

    public boolean phoneMatches(UUID account, String inputPhone) {
        final String phone;
        try { phone = InternationalPhone.normalize(inputPhone); }
        catch (IllegalArgumentException ex) { return false; }
        var rows = jdbc.queryForList("SELECT phone_e164 FROM users WHERE id=?", String.class, account.toString());
        return !rows.isEmpty() && rows.getFirst().equals(phone);
    }

    public void claim(UUID account, String pin) {
        transaction();
        SixDigitPinEncoder.validateForCreation(pin);
        var rows = jdbc.queryForList("SELECT u.pin_hash FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND r.\"key\"='MEMBER' AND u.status='PENDING' FOR UPDATE",
            String.class, account.toString());
        if (rows.isEmpty() || !rows.getFirst().startsWith(UNSET_PREFIX)) throw new AuthFailure(409, "ACCOUNT_ALREADY_CLAIMED");
        jdbc.update("UPDATE users SET pin_hash=?,pin_changed_at=?,status='ACTIVE',failed_pin_attempts=0,locked_until=NULL WHERE id=?",
            encoder.encode(pin), Timestamp.from(clock.instant()), account.toString());
    }

    public void resetPin(UUID account, String pin) {
        transaction();
        SixDigitPinEncoder.validateForCreation(pin);
        var rows = jdbc.queryForList("SELECT u.status FROM users u JOIN roles r ON r.id=u.role_id WHERE u.id=? AND r.\"key\"='MEMBER' FOR UPDATE",
            String.class, account.toString());
        if (rows.isEmpty()) throw new AuthFailure(409, "TARGET_MUST_BE_MEMBER");
        // An unclaimed account sets its first PIN only through the physical card (decision 44).
        if (!"ACTIVE".equals(rows.getFirst())) throw new AuthFailure(409, "MEMBER_NOT_CLAIMED");
        var now = Timestamp.from(clock.instant());
        jdbc.update("UPDATE users SET pin_hash=?,pin_changed_at=?,failed_pin_attempts=0,locked_until=NULL WHERE id=?",
            encoder.encode(pin), now, account.toString());
        jdbc.update("UPDATE identity_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", now, account.toString());
    }
}
