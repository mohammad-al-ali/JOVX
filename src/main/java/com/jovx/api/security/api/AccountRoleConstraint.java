package com.jovx.api.security.api;

import java.util.UUID;

/** Domain modules veto a role change that would detach an existing business identity. */
public interface AccountRoleConstraint {
    boolean permits(UUID userId, String targetRole);
}
