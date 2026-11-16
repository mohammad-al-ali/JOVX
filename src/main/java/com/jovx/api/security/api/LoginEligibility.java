package com.jovx.api.security.api;

import java.util.UUID;

/** Implemented by owning business modules; security never reads their tables. */
public interface LoginEligibility {
    boolean supports(String role);
    boolean allowed(UUID accountId, String role);
}
