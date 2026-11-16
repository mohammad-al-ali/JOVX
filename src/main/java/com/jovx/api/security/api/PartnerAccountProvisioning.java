package com.jovx.api.security.api;

import java.util.UUID;

/** Trusted module API. Caller must enforce administrative authorization and own the use-case transaction. */
public interface PartnerAccountProvisioning {
    UUID createPending(String name, String phone, String pin);
    void activate(UUID accountId);
}
