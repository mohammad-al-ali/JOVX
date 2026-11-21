package com.jovx.api.points.api;

import java.time.Instant;
import java.util.UUID;

/** Takes whole points back from a member (sale cancellation). The caller owns the transaction. */
public interface PointsDebits {
  /** Returns false, changing nothing, when the balance is below the points: it never goes negative. */
  boolean debit(UUID memberAccount, long points, String kind, UUID reference, Instant now);
}
