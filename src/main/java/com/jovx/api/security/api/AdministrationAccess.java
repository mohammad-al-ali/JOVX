package com.jovx.api.security.api;

import java.util.Set;
import java.util.UUID;

/** Call inside the command transaction: locks actor state before a privileged write. */
public interface AdministrationAccess {
    void require(UUID accountId, UUID sessionId, String permission);
    /** SUPER_ADMIN, or ADMIN holding at least one of the permissions. */
    void requireAny(UUID accountId, UUID sessionId, Set<String> permissions);
}
