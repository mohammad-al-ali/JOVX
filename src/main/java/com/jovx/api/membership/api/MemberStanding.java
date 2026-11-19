package com.jovx.api.membership.api;

import java.util.UUID;

/** Read-only membership facts for other modules (points freeze, point of sale). */
public interface MemberStanding {
    /** False for a non-member, an expired membership, or a member without an assigned card. */
    boolean canTransact(UUID memberAccount);

    /** True while the membership term has not ended (card state ignored). */
    boolean termActive(UUID memberAccount);
}
