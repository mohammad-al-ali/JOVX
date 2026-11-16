package com.jovx.api.security.api;

import java.util.UUID;

/** Call inside the command transaction: locks actor state before a privileged write. */
public interface AdministrationAccess {
    void require(UUID accountId, UUID sessionId, String permission);
}
