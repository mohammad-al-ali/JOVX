package com.jovx.api.security.internal.accounts;

import com.jovx.api.security.api.LoginEligibility;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Administration accounts have no affiliation to check: an ACTIVE account may log in.
 *
 * <p>Login is allowed only for roles that some {@link LoginEligibility} supports. A new role
 * (e.g. ADMIN_EMPLOYEE) is enabled by registering its policy, not by editing the session service.
 */
@Component
@Profile("local-postgres")
final class AdministrationLoginPolicy implements LoginEligibility {
    @Override public boolean supports(String role) {
        return AccountRole.SUPER_ADMIN.name().equals(role) || AccountRole.ADMIN.name().equals(role);
    }

    @Override public boolean allowed(UUID accountId, String role) {
        return supports(role);
    }
}
