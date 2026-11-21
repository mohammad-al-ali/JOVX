package com.jovx.api.points.api;

import java.time.Instant;
import java.util.UUID;

/** Adds whole points to a member's balance with a ledger row. The caller owns the transaction. */
public interface PointsCredits {
  /** kind: e.g. SALE; reference: the sale id shown with the movement. */
  void credit(UUID memberAccount, long points, String kind, UUID reference, Instant now);
}
