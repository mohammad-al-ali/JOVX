package com.jovx.api.security.internal.accounts;

import java.util.List;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

public final class AccountPrincipal extends User {
    private final UUID accountId;
    private final AccountRole role;

    public AccountPrincipal(UUID accountId, String phone, String hash, AccountRole role, boolean active) {
        super(phone, hash, active, true, true, true,
            List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        this.accountId = accountId;
        this.role = role;
    }

    public UUID accountId() { return accountId; }
    public AccountRole role() { return role; }
    @Override public String toString() { return "AccountPrincipal[credentials and phone redacted]"; }
}
