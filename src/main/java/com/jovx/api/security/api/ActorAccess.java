package com.jovx.api.security.api;

import java.util.UUID;

/** Recheck the current actor/session inside the caller's command transaction. */
public interface ActorAccess {
    void requireRole(UUID userId,UUID sessionId,String role);
    void requirePermission(UUID userId,UUID sessionId,String permission);
}
