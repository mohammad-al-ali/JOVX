package com.jovx.api.wallets.api;

import java.time.Instant;
import java.util.UUID;

/** Points of a cancelled sale return to the partner wallet. The caller owns the transaction. */
public interface WalletCredits {
  void returnSalePoints(UUID partnerId, long points, UUID saleId, UUID actor, Instant now);
}
