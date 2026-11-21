package com.jovx.api.catalog.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CatalogModels {
  private CatalogModels() {}

  public static final int MAX_IMAGES = 4;
  public static final int MAX_STOCK = 1_000_000;
  public static final long MAX_PRICE = 1_000_000_000_000L;

  public enum ReviewStatus { PENDING, APPROVED, REJECTED, HIDDEN }

  public record Category(UUID id, String name, int sortOrder, boolean active) {}

  /** Public product browsing order; NEWEST is the default. Prices compare the member price. */
  public enum ProductSort { NEWEST, PRICE_ASC, PRICE_DESC, SAVING_DESC, POINTS_DESC, NAME }

  /** A validated public query: every filter is optional; text is already normalized (ArabicText). */
  public record ProductSearch(
      String text, UUID categoryId, UUID cityId, UUID storeId, Long minPrice, Long maxPrice,
      boolean inStockOnly, ProductSort sort, int offset, int limit) {
    public static final int DEFAULT_LIMIT = 20, MAX_LIMIT = 50, MAX_OFFSET = 10_000, MAX_TEXT = 100;
  }

  /** A visible product with its member price as computed by the database query. */
  public record SearchHit(Product product, long memberPrice) {}

  public record SearchPage(long total, List<SearchHit> hits) {}

  /**
   * Search normalization shared with the SQL function jovx_ar_norm (V13): lower case, no tashkeel or
   * tatweel, and أ/إ/آ→ا, ى→ي, ة→ه, so «أحمد» finds «احمد» and «حلويات» finds «حلويّات».
   */
  public static final class ArabicText {
    private ArabicText() {}

    public static String normalize(String text) {
      if (text == null) return "";
      var out = new StringBuilder(text.length());
      for (char c : text.strip().toLowerCase(java.util.Locale.ROOT).toCharArray()) {
        if ((c >= 'ً' && c <= 'ٟ') || c == 'ٰ' || c == 'ـ') continue;
        out.append(switch (c) {
          case 'أ', 'إ', 'آ' -> 'ا';
          case 'ى' -> 'ي';
          case 'ة' -> 'ه';
          default -> c;
        });
      }
      return out.toString().replaceAll("\s+", " ");
    }
  }

  public record Product(
      UUID id, UUID requestId, UUID storeId, UUID partnerId, UUID categoryId, String name, String description,
      long priceAmount, String currency, Long memberPriceAmount, long points, boolean stockTracked, Integer stock,
      ReviewStatus reviewStatus, String reviewReason, boolean published, int version, Instant createdAt,
      Instant updatedAt) {}

  public record ProductImage(UUID id, UUID productId, int position, String objectKey, String contentType, int sizeBytes) {}

  /** What a partner may set; stock and the show/hide switch have their own commands. */
  public record ProductDraft(
      UUID categoryId, String name, String description, long priceAmount, Long memberPriceAmount, long points,
      boolean stockTracked, Integer stock) {}

  /** Pure catalog rules. */
  public static final class Rules {
    private Rules() {}

    /** Half-up rounding to a whole unit: 0.5 or more rounds up (decision 12). */
    static long halfUp(BigDecimal value) {
      return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** Decisions 11, 14: at least 2% of the price before discount (10,000 → 200; 12,345 → 247). */
    public static long minimumPoints(long priceAmount) {
      return halfUp(BigDecimal.valueOf(priceAmount).multiply(new BigDecimal("0.02")));
    }

    /** Decision 65: automatic member price = price × (1 − minimum discount%) rounded half-up. */
    public static long automaticMemberPrice(long priceAmount, BigDecimal minimumDiscountPercent) {
      var factor = BigDecimal.valueOf(100).subtract(minimumDiscountPercent).divide(BigDecimal.valueOf(100));
      return halfUp(BigDecimal.valueOf(priceAmount).multiply(factor));
    }

    /** A custom member price may only give a discount at least as large as the partner-wide minimum. */
    public static boolean validCustomMemberPrice(long custom, long priceAmount, BigDecimal minimumDiscountPercent) {
      return custom >= 0 && custom <= automaticMemberPrice(priceAmount, minimumDiscountPercent);
    }

    public static long effectiveMemberPrice(Product p, BigDecimal minimumDiscountPercent) {
      return p.memberPriceAmount() != null ? p.memberPriceAmount() : automaticMemberPrice(p.priceAmount(), minimumDiscountPercent);
    }

    /** Decision 24: anything but stock and the show/hide switch sends the product back to review. */
    public static boolean needsReview(Product before, ProductDraft after) {
      return !before.categoryId().equals(after.categoryId()) || !before.name().equals(after.name())
          || !java.util.Objects.equals(before.description(), after.description())
          || before.priceAmount() != after.priceAmount()
          || !java.util.Objects.equals(before.memberPriceAmount(), after.memberPriceAmount())
          || before.points() != after.points() || before.stockTracked() != after.stockTracked();
    }

    /** Shown to members: approved, switched on by the partner, and the store and partner active. */
    public static boolean visibleToMembers(Product p, boolean storeActive, boolean partnerActive) {
      return p.reviewStatus() == ReviewStatus.APPROVED && p.published() && storeActive && partnerActive;
    }
  }
}
