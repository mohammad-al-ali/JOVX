package com.jovx.api.sales.dto;

import com.jovx.api.sales.model.SalesModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class SalesDtos {
  private SalesDtos() {}

  // ---- requests ----

  /** code: the member app's QR text (JX2.…) or the printed card's barcode text. */
  public record ScanRequest(@NotNull UUID storeId, @NotBlank @Size(max = 200) String code) {}

  public record LineRequest(@NotNull UUID productId, @NotNull @Min(1) @Max(MAX_QUANTITY_VALUE) Integer quantity) {}

  /**
   * requestId is created once per invoice screen; a retried submit returns the first sale.
   * PRODUCTS: lines. FREE: grossAmount (before discount), discountPercent and points; both optional and
   * then the partner's minimum discount and 2% points (decisions 6, 10).
   */
  public record SaleRequest(
      @NotNull UUID requestId,
      @NotNull UUID scanId,
      @NotNull SaleKind kind,
      @Size(max = 50) List<@Valid LineRequest> lines,
      @Positive Long grossAmount,
      @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal discountPercent,
      @PositiveOrZero Long points) {}

  public static final int MAX_QUANTITY_VALUE = 1000;

  // ---- responses ----

  public record StoreOption(UUID id, String name, boolean active) {}

  /** What the signed-in seller may do; the app shows only these actions. */
  public record Abilities(
      boolean scan, boolean sellProducts, boolean freeSale, boolean readOwnSales, boolean readStoreSales,
      boolean reports) {}

  public record SalesContext(
      String partnerName, boolean partnerActive, BigDecimal minimumDiscountPercent, boolean owner,
      List<StoreOption> stores, Abilities can) {}

  public record SellableProductView(
      UUID id, String name, long priceAmount, long memberPrice, String currency, long points, Integer stock,
      boolean outOfStock, String coverImageUrl) {}

  public record MemberView(String name, String memberNumber, String maskedCard) {}

  public record ScanView(
      UUID scanId, UUID storeId, Instant expiresAt, MemberView member, BigDecimal minimumDiscountPercent) {}

  public record LineView(
      UUID productId, String name, long unitPrice, long unitMemberPrice, long unitPoints, int quantity,
      long lineNet, long linePoints) {
    public static LineView of(SaleLine l) {
      return new LineView(l.productId(), l.name(), l.unitPrice(), l.unitMemberPrice(), l.unitPoints(), l.quantity(),
          l.net(), l.points());
    }
  }

  /** Receipt (decision 15): amount, saving and points; lines for a product invoice. */
  public record SaleView(
      UUID id, Instant createdAt, UUID storeId, String storeName, String partnerName, SaleKind kind, String currency,
      long grossAmount, long discountAmount, long netAmount, BigDecimal discountPercent, long points,
      MemberView member, String sellerName, List<LineView> lines) {}

  public record TopProductView(UUID productId, String name, long quantity, long net) {}

  /** Decision 39: sales, customers, points and best sellers for a Damascus-time period. */
  public record SalesSummary(
      int days, Instant since, long sales, long customers, long grossAmount, long discountAmount, long netAmount,
      long points, List<TopProductView> topProducts) {}

  public record MemberPurchases(long purchases, long totalSavings, long totalPoints, List<SaleView> items) {}
}
