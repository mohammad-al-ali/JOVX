package com.jovx.api.catalog.service;

import static com.jovx.api.catalog.service.CatalogService.fail;

import com.jovx.api.catalog.dto.CatalogDtos.*;
import com.jovx.api.catalog.model.CatalogModels.*;
import com.jovx.api.catalog.repository.CatalogRepository;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.partners.api.PartnerLookup.PartnerRef;
import com.jovx.api.partners.api.PartnerLookup.StoreRef;
import com.jovx.api.security.api.AdministrationAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Decision 27: approve, reject with a reason, or hide with a reason — never edit or delete. */
@Service
@Profile("local-postgres")
public class ProductReviewService {
  static final String PERMISSION = "catalog.review";
  private final CatalogRepository repository;
  private final CatalogService catalog;
  private final PartnerLookup partners;
  private final AdministrationAccess access;
  private final TransactionTemplate tx;
  private final Clock clock;

  public ProductReviewService(CatalogRepository repository, CatalogService catalog, PartnerLookup partners,
      AdministrationAccess access, @Qualifier("catalogTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.catalog = catalog; this.partners = partners; this.access = access;
    this.tx = tx; this.clock = clock;
  }

  public List<ProductView> queue(UUID actor, UUID sid, ReviewStatus status) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      var products = repository.reviewQueue(status, 200);
      var stores = new HashMap<UUID, StoreRef>();
      var owners = new HashMap<UUID, PartnerRef>();
      var result = new ArrayList<ProductView>();
      for (var p : products) {
        var store = stores.computeIfAbsent(p.storeId(), id -> partners.store(id).orElse(null));
        var partner = owners.computeIfAbsent(p.partnerId(), id -> partners.byId(id).orElseThrow());
        result.add(catalog.view(p, store, partner));
      }
      return result;
    });
  }

  public ProductView approve(UUID actor, UUID sid, UUID productId) {
    return decide(actor, sid, productId, ReviewStatus.APPROVED, null, "PRODUCT_APPROVED");
  }

  public ProductView reject(UUID actor, UUID sid, UUID productId, String reason) {
    return decide(actor, sid, productId, ReviewStatus.REJECTED, reason, "PRODUCT_REJECTED");
  }

  public ProductView hide(UUID actor, UUID sid, UUID productId, String reason) {
    return decide(actor, sid, productId, ReviewStatus.HIDDEN, reason, "PRODUCT_HIDDEN");
  }

  private ProductView decide(UUID actor, UUID sid, UUID productId, ReviewStatus status, String reason, String action) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      var product = repository.lock(productId).orElseThrow(() -> fail(Kind.NOT_FOUND, "PRODUCT_NOT_FOUND"));
      String clean = reason == null ? null : reason.strip();
      if (status != ReviewStatus.APPROVED && (clean == null || clean.isEmpty())) throw fail(Kind.INVALID, "REASON_REQUIRED");
      if (status == ReviewStatus.APPROVED && product.reviewStatus() != ReviewStatus.PENDING)
        throw fail(Kind.CONFLICT, "PRODUCT_NOT_PENDING");
      if (status == ReviewStatus.REJECTED && product.reviewStatus() != ReviewStatus.PENDING)
        throw fail(Kind.CONFLICT, "PRODUCT_NOT_PENDING");
      if (status == ReviewStatus.HIDDEN && product.reviewStatus() != ReviewStatus.APPROVED)
        throw fail(Kind.CONFLICT, "PRODUCT_NOT_APPROVED");
      Instant now = clock.instant();
      repository.review(productId, status, clean, actor, now);
      repository.event(productId, actor, action, clean, now);
      var store = partners.store(product.storeId()).orElse(null);
      var partner = partners.byId(product.partnerId()).orElseThrow();
      return catalog.view(repository.product(productId).orElseThrow(), store, partner);
    });
  }

  // ---- product categories (decision 26) ----

  public List<CategoryView> categories(UUID actor, UUID sid) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      return repository.categories(false).stream().map(CategoryView::of).toList();
    });
  }

  public CategoryView saveCategory(UUID actor, UUID sid, UUID id, CategoryRequest r) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      String name = r.name().strip().replaceAll("\\s+", " ");
      if (name.isEmpty()) throw fail(Kind.INVALID, "INVALID_CATEGORY_NAME");
      var category = new Category(id == null ? UUID.randomUUID() : id, name, r.sortOrder(), r.active());
      try {
        if (id == null) repository.insertCategory(category);
        else if (!repository.updateCategory(category)) throw fail(Kind.NOT_FOUND, "CATEGORY_NOT_FOUND");
      } catch (DuplicateKeyException e) {
        throw fail(Kind.CONFLICT, "CATEGORY_NAME_EXISTS");
      }
      return CategoryView.of(category);
    });
  }
}
