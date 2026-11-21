package com.jovx.api.catalog.service;

import com.jovx.api.catalog.api.ProductSales;
import com.jovx.api.catalog.model.CatalogModels.*;
import com.jovx.api.catalog.repository.CatalogRepository;
import com.jovx.api.catalog.storage.ImageStore;
import com.jovx.api.partners.api.PartnerLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** The catalog seen by the point of sale; prices follow the same rules as the member's store page. */
@Service
@Profile("local-postgres")
public class ProductSaleService implements ProductSales {
  private final CatalogRepository repository;
  private final PartnerLookup partners;
  private final ImageStore images;

  public ProductSaleService(CatalogRepository repository, PartnerLookup partners, ImageStore images) {
    this.repository = repository; this.partners = partners; this.images = images;
  }

  public List<SellableProduct> sellableIn(UUID storeId) {
    var products = repository.storeProducts(storeId);
    if (products.isEmpty()) return List.of();
    var discount = minimumDiscount(products.getFirst().partnerId());
    var stored = repository.images(products.stream().map(Product::id).toList());
    return products.stream().map(p -> sellable(p, discount, stored.getOrDefault(p.id(), List.of())))
        .filter(SellableProduct::sellable).toList();
  }

  public Optional<SellableProduct> lockForSale(UUID productId) {
    return repository.lock(productId).map(p -> sellable(p, minimumDiscount(p.partnerId()), List.of()));
  }

  public void takeStock(UUID productId, int quantity, Instant now) {
    var p = repository.lock(productId).orElseThrow();
    if (!p.stockTracked()) return;
    if (p.stock() < quantity) throw new IllegalStateException("Stock checked by the caller");
    repository.updateStock(productId, p.stock() - quantity, now);
  }

  public void returnStock(UUID productId, int quantity, Instant now) {
    repository.lock(productId).filter(Product::stockTracked)
        .ifPresent(p -> repository.updateStock(productId, Math.min(com.jovx.api.catalog.model.CatalogModels.MAX_STOCK, p.stock() + quantity), now));
  }

  private BigDecimal minimumDiscount(UUID partnerId) {
    return partners.byId(partnerId).map(PartnerLookup.PartnerRef::minimumDiscountPercent).orElse(BigDecimal.ZERO);
  }

  private SellableProduct sellable(Product p, BigDecimal discount, List<ProductImage> stored) {
    return new SellableProduct(p.id(), p.storeId(), p.partnerId(), p.name(), p.priceAmount(),
        Rules.effectiveMemberPrice(p, discount), p.currency(), p.points(), p.stockTracked() ? p.stock() : null,
        p.reviewStatus() == ReviewStatus.APPROVED && p.published(),
        stored.isEmpty() ? null : images.publicUrl(stored.getFirst().objectKey()));
  }
}
