package com.jovx.api.catalog.api;

import java.time.Instant;
import java.util.*;

/** What the point of sale needs from the catalog. The caller owns the transaction. */
public interface ProductSales {
  /** Approved products the partner shows to members, at today's member price (decision 8). */
  List<SellableProduct> sellableIn(UUID storeId);

  /** Locks one product row for the sale. Empty when it does not exist or was deleted. */
  Optional<SellableProduct> lockForSale(UUID productId);

  /** Lowers tracked stock after {@link #lockForSale}; the caller already checked the quantity. */
  void takeStock(UUID productId, int quantity, Instant now);

  /**
   * memberPrice: custom, or automatic from the partner's minimum discount (decision 65).
   * stock: null when not tracked. sellable: approved and shown (decision 66).
   */
  record SellableProduct(
      UUID id, UUID storeId, UUID partnerId, String name, long priceAmount, long memberPrice, String currency,
      long points, Integer stock, boolean sellable, String coverImageUrl) {
    public boolean outOfStock() {
      return stock != null && stock == 0;
    }
  }
}
