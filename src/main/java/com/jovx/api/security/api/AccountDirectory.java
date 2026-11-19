package com.jovx.api.security.api;

import java.util.*;

/** Read-only account facts for business modules; never exposes PIN hashes or session data. */
public interface AccountDirectory {
    Map<UUID, AccountSummary> summaries(Collection<UUID> accountIds);

    /** Accounts of one role whose name contains the text or whose phone contains its digits (>=3). */
    List<UUID> search(String role, String text, int limit);

    record AccountSummary(UUID id, String name, String phone, String status) { }
}
