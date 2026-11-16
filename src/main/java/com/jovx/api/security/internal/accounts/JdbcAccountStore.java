package com.jovx.api.security.internal.accounts;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/** Identity-owned persistence only. No HTTP registration or employee-authorization bypass. */
public final class JdbcAccountStore implements UserDetailsService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final PasswordEncoder encoder;

    public JdbcAccountStore(DataSource source, PasswordEncoder encoder) {
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new JdbcTransactionManager(source));
        this.encoder = encoder;
    }

    public void create(UUID id, String name, String inputPhone, AccountRole role, String pin) {
        create(id, name, inputPhone, role, pin, "ACTIVE");
    }

    public void createPendingPartner(UUID id, String name, String phone, String pin) {
        create(id, name, phone, AccountRole.PARTNER, pin, "PENDING");
    }

    private void create(UUID id, String name, String inputPhone, AccountRole role, String pin, String state) {
        Objects.requireNonNull(id);
        Objects.requireNonNull(role);
        if (name == null || name.isBlank() || name.strip().length() > 200) throw new IllegalArgumentException("Name is required");
        SixDigitPinEncoder.validateForCreation(pin);
        String phone = InternationalPhone.normalize(inputPhone);
        String hash = encoder.encode(pin);
        transactions.executeWithoutResult(status -> {
            int inserted = jdbc.update("INSERT INTO users (id,name,phone_e164,role_id,status,pin_hash) SELECT ?,?,?,id,?,? FROM roles WHERE \"key\"=?",
                id.toString(), name.strip(), phone, state, hash, role.name());
            if (inserted != 1) throw new IllegalArgumentException("Unknown predefined role");
        });
    }

    @Override public UserDetails loadUserByUsername(String username) {
        final String phone;
        try { phone = InternationalPhone.normalize(username); }
        catch (IllegalArgumentException ex) { throw new UsernameNotFoundException("Invalid credentials"); }
        var users = jdbc.query("""
            SELECT a.id, a.phone_e164, r.\"key\" AS role, a.status, a.pin_hash
            FROM users a JOIN roles r ON r.id = a.role_id
            WHERE a.phone_e164 = ?
            """, (row, index) -> new AccountPrincipal(UUID.fromString(row.getString("id")),
                row.getString("phone_e164"), row.getString("pin_hash"),
                AccountRole.valueOf(row.getString("role")), "ACTIVE".equals(row.getString("status"))), phone);
        if (users.size() != 1) throw new UsernameNotFoundException("Invalid credentials");
        return users.getFirst();
    }
}
