package com.jovx.api.security.api;

import java.util.*;

/** Read-only account facts for business modules; never exposes PIN hashes or session data. */
public interface AccountDirectory {
    Map<UUID, AccountSummary> summaries(Collection<UUID> accountIds);

    record AccountSummary(UUID id, String name, String phone, String status) { }
}
