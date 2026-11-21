package com.jovx.api.wallets.api;

import java.time.Instant;
import java.util.UUID;

/** Sale points leave the partner's prepaid wallet (decisions 6, 71, 72). The caller owns the transaction. */
public interface WalletDebits {
  /**
   * Takes the points from the spendable balance (held refund points are never used).
   * Returns false, changing nothing, when the balance is too low: the wallet never goes negative.
   */
  boolean debitSalePoints(UUID partnerId, long points, UUID saleId, UUID actor, Instant now);
}
