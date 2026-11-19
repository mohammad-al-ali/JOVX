package com.jovx.api.catalog.dto;

import com.jovx.api.catalog.model.CatalogModels.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class CatalogDtos {
  private CatalogDtos() {}

  /** memberPriceAmount null = automatic member price (decision 65). stock only when stockTracked. */
  public record ProductRequest(
      @NotNull UUID categoryId,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 2000) String description,
      @NotNull @Positive @Max(1_000_000_000_000L) Long priceAmount,
      @PositiveOrZero Long memberPriceAmount,
      @NotNull @PositiveOrZero Long points,
      @NotNull Boolean stockTracked,
      @Min(0) @Max(1_000_000) Integer stock) {}

  public record CreateProductRequest(@NotNull UUID requestId, @NotNull ProductRequest product) {}

  /** expectedVersion guards against overwriting a newer edit (PRODUCT_CHANGED_RELOAD). */
  public record UpdateProductRequest(@NotNull Integer expectedVersion, @NotNull ProductRequest product) {}

  public record StockRequest(@Min(0) @Max(1_000_000) Integer stock) {}

  public record PublishedRequest(@NotNull Boolean published) {}

  public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

  public record CategoryRequest(@NotBlank @Size(max = 100) String name, @NotNull @Min(0) @Max(1000) Integer sortOrder,
      @NotNull Boolean active) {}

  public record CategoryView(UUID id, String name, int sortOrder, boolean active) {
    public static CategoryView of(Category c) {
      return new CategoryView(c.id(), c.name(), c.sortOrder(), c.active());
    }
  }

  public record ImageView(UUID id, int position, String url) {}

  /** For the partner and the administration: everything about one product. */
  public record ProductView(
      UUID id, UUID storeId, String storeName, UUID categoryId, String categoryName, String name, String description,
      long priceAmount, String currency, boolean customMemberPrice, long memberPriceAmount, long minimumPoints,
      long points, boolean stockTracked, Integer stock, ReviewStatus reviewStatus, String reviewReason,
      boolean published, boolean visibleToMembers, int version, List<ImageView> images, Instant updatedAt) {}

  /** For members and guests: only what is shown in the store. */
  public record PublicProductView(
      UUID id, String name, String description, String categoryName, long priceAmount, long memberPriceAmount,
      String currency, long points, boolean outOfStock, List<String> imageUrls) {}

  /** Image upload result; the product went back to review. */
  public record ImageUploadResult(ProductView product) {}
}
