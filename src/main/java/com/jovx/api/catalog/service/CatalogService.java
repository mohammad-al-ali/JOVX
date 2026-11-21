package com.jovx.api.catalog.service;

import com.jovx.api.catalog.dto.CatalogDtos.*;
import static com.jovx.api.catalog.model.CatalogModels.*;
import com.jovx.api.catalog.model.CatalogModels.*;
import com.jovx.api.catalog.repository.CatalogRepository;
import com.jovx.api.catalog.storage.ImageProcessor;
import com.jovx.api.catalog.storage.ImageStore;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.partners.api.PartnerLookup.PartnerRef;
import com.jovx.api.partners.api.PartnerLookup.StoreRef;
import com.jovx.api.security.api.ActorAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The partner's store catalog. The owning partner works on all its stores; an employee only on
 * assigned stores, reading with catalog.read and writing with catalog.submit (decision 68).
 */
@Service
@Profile("local-postgres")
public class CatalogService {
  private final CatalogRepository repository;
  private final PartnerLookup partners;
  private final ActorAccess actors;
  private final ImageStore images;
  private final ImageProcessor processor;
  private final TransactionTemplate tx;
  private final Clock clock;

  public CatalogService(CatalogRepository repository, PartnerLookup partners, ActorAccess actors, ImageStore images,
      ImageProcessor processor, @Qualifier("catalogTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.partners = partners; this.actors = actors; this.images = images;
    this.processor = processor; this.tx = tx; this.clock = clock;
  }

  record Scope(StoreRef store, PartnerRef partner) {}

  /** Resolves and authorizes the actor for one store inside the caller's transaction. */
  Scope scope(UUID actor, UUID sid, String role, UUID storeId, boolean write) {
    StoreRef store = partners.store(storeId).orElseThrow(() -> fail(Kind.NOT_FOUND, "STORE_NOT_FOUND"));
    PartnerRef partner;
    if ("PARTNER".equals(role)) {
      actors.requireRole(actor, sid, "PARTNER");
      partner = partners.ownedBy(actor).orElseThrow(() -> fail(Kind.FORBIDDEN, "FORBIDDEN"));
    } else {
      actors.requirePermission(actor, sid, write ? "catalog.submit" : "catalog.read");
      partner = partners.employerOf(actor).orElseThrow(() -> fail(Kind.FORBIDDEN, "FORBIDDEN"));
      if (!partners.employeeStores(actor).contains(storeId)) throw fail(Kind.NOT_FOUND, "STORE_NOT_FOUND");
    }
    if (!store.partnerId().equals(partner.id())) throw fail(Kind.NOT_FOUND, "STORE_NOT_FOUND");
    return new Scope(store, partner);
  }

  private Product owned(UUID actor, UUID sid, String role, UUID productId, boolean lock) {
    var product = (lock ? repository.lock(productId) : repository.product(productId))
        .orElseThrow(() -> fail(Kind.NOT_FOUND, "PRODUCT_NOT_FOUND"));
    scope(actor, sid, role, product.storeId(), true);
    return product;
  }

  // ---- reads ----

  public List<CategoryView> activeCategories() {
    return repository.categories(true).stream().map(CategoryView::of).toList();
  }

  public List<ProductView> storeProducts(UUID actor, UUID sid, String role, UUID storeId) {
    return tx.execute(s -> {
      var scope = scope(actor, sid, role, storeId, false);
      return views(repository.storeProducts(storeId), Map.of(storeId, scope.store()), scope.partner());
    });
  }

  // ---- writes ----

  public ProductView create(UUID actor, UUID sid, String role, UUID storeId, CreateProductRequest r) {
    return tx.execute(s -> {
      var scope = scope(actor, sid, role, storeId, true);
      var replay = repository.byRequest(r.requestId());
      if (replay.isPresent()) {
        if (!replay.get().storeId().equals(storeId)) throw fail(Kind.CONFLICT, "REQUEST_ID_REUSED");
        return view(replay.get(), scope.store(), scope.partner());
      }
      var draft = validDraft(r.product(), scope.partner());
      Instant now = clock.instant();
      var product = new Product(UUID.randomUUID(), r.requestId(), storeId, scope.partner().id(), draft.categoryId(),
          draft.name(), draft.description(), draft.priceAmount(), "SYP", draft.memberPriceAmount(), draft.points(),
          draft.stockTracked(), draft.stock(), ReviewStatus.PENDING, null, true, 1, now, now);
      repository.insertProduct(product, actor);
      repository.event(product.id(), actor, "PRODUCT_CREATED", null, now);
      return view(product, scope.store(), scope.partner());
    });
  }

  public ProductView update(UUID actor, UUID sid, String role, UUID productId, UpdateProductRequest r) {
    return tx.execute(s -> {
      var before = owned(actor, sid, role, productId, true);
      if (before.version() != r.expectedVersion()) throw fail(Kind.CONFLICT, "PRODUCT_CHANGED_RELOAD");
      var scope = scope(actor, sid, role, before.storeId(), true);
      var draft = validDraft(r.product(), scope.partner());
      Instant now = clock.instant();
      boolean review = Rules.needsReview(before, draft);
      var status = review ? ReviewStatus.PENDING : before.reviewStatus();
      repository.updateDetails(productId, draft, status, review ? null : before.reviewReason(), now);
      repository.event(productId, actor, review ? "PRODUCT_RESUBMITTED" : "PRODUCT_UPDATED", null, now);
      return view(repository.product(productId).orElseThrow(), scope.store(), scope.partner());
    });
  }

  /** Decision 24: stock alone never sends the product back to review. */
  public ProductView setStock(UUID actor, UUID sid, String role, UUID productId, Integer stock) {
    return tx.execute(s -> {
      var before = owned(actor, sid, role, productId, true);
      if (!before.stockTracked()) throw fail(Kind.INVALID, "STOCK_NOT_TRACKED");
      if (stock == null || stock < 0 || stock > MAX_STOCK) throw fail(Kind.INVALID, "INVALID_STOCK");
      repository.updateStock(productId, stock, clock.instant());
      var scope = scope(actor, sid, role, before.storeId(), true);
      return view(repository.product(productId).orElseThrow(), scope.store(), scope.partner());
    });
  }

  /** Decision 66: the partner's own switch, no new review. */
  public ProductView setPublished(UUID actor, UUID sid, String role, UUID productId, boolean published) {
    return tx.execute(s -> {
      var before = owned(actor, sid, role, productId, true);
      Instant now = clock.instant();
      repository.updatePublished(productId, published, now);
      repository.event(productId, actor, published ? "PRODUCT_SHOWN" : "PRODUCT_UNPUBLISHED", null, now);
      var scope = scope(actor, sid, role, before.storeId(), true);
      return view(repository.product(productId).orElseThrow(), scope.store(), scope.partner());
    });
  }

  /** Decision 27: deletion belongs to the partner only. Rows are kept for history; images are removed. */
  public void delete(UUID actor, UUID sid, String role, UUID productId) {
    var keys = tx.execute(s -> {
      owned(actor, sid, role, productId, true);
      Instant now = clock.instant();
      var stored = repository.images(productId);
      repository.softDelete(productId, now);
      repository.event(productId, actor, "PRODUCT_DELETED", null, now);
      return stored.stream().map(ProductImage::objectKey).toList();
    });
    keys.forEach(this::quietDelete);
  }

  public ProductView addImage(UUID actor, UUID sid, String role, UUID productId, byte[] upload) {
    if (!images.configured()) throw fail(Kind.UNAVAILABLE, "IMAGE_STORAGE_NOT_CONFIGURED");
    ImageProcessor.Processed processed;
    try { processed = processor.process(upload); }
    catch (ImageProcessor.Rejected e) {
      throw fail(Kind.INVALID, switch (e.failure()) {
        case TOO_LARGE -> "IMAGE_TOO_LARGE";
        case UNSUPPORTED -> "IMAGE_TYPE_NOT_ALLOWED";
        case UNREADABLE -> "IMAGE_UNREADABLE";
      });
    }
    // Authorize before storing anything; store outside the transaction, then record it.
    tx.executeWithoutResult(s -> {
      owned(actor, sid, role, productId, false);
      if (repository.images(productId).size() >= MAX_IMAGES) throw fail(Kind.CONFLICT, "TOO_MANY_IMAGES");
    });
    String key = "products/" + productId + "/" + UUID.randomUUID() + ".jpg";
    images.put(key, processed.bytes(), processed.contentType());
    try {
      return tx.execute(s -> {
        var product = owned(actor, sid, role, productId, true);
        var existing = repository.images(productId);
        if (existing.size() >= MAX_IMAGES) throw fail(Kind.CONFLICT, "TOO_MANY_IMAGES");
        Instant now = clock.instant();
        repository.insertImage(new ProductImage(UUID.randomUUID(), productId, existing.size(), key,
            processed.contentType(), processed.bytes().length), now);
        repository.backToReview(productId, now);
        repository.event(productId, actor, "IMAGE_ADDED", null, now);
        var scope = scope(actor, sid, role, product.storeId(), true);
        return view(repository.product(productId).orElseThrow(), scope.store(), scope.partner());
      });
    } catch (RuntimeException e) {
      quietDelete(key); // nothing recorded: remove the orphan object
      throw e;
    }
  }

  public ProductView removeImage(UUID actor, UUID sid, String role, UUID productId, UUID imageId) {
    var result = tx.execute(s -> {
      var product = owned(actor, sid, role, productId, true);
      var removed = repository.deleteImage(productId, imageId).orElseThrow(() -> fail(Kind.NOT_FOUND, "IMAGE_NOT_FOUND"));
      Instant now = clock.instant();
      repository.backToReview(productId, now);
      repository.event(productId, actor, "IMAGE_REMOVED", null, now);
      var scope = scope(actor, sid, role, product.storeId(), true);
      return Map.entry(removed.objectKey(), view(repository.product(productId).orElseThrow(), scope.store(), scope.partner()));
    });
    quietDelete(result.getKey());
    return result.getValue();
  }

  // ---- public ----

  /** Every visible product of one store, by name; 404 when the store or its partner is not active. */
  public List<PublicProductView> publicStoreProducts(UUID storeId) {
    var store = partners.listedStores().stream().filter(st -> st.id().equals(storeId)).findFirst()
        .orElseThrow(() -> fail(Kind.NOT_FOUND, "STORE_NOT_FOUND"));
    var products = repository.storeProducts(storeId).stream()
        .filter(p -> Rules.visibleToMembers(p, true, true)).toList();
    return publicViews(products.stream()
        .map(p -> new SearchHit(p, Rules.effectiveMemberPrice(p, store.minimumDiscountPercent()))).toList(),
        Map.of(store.id(), store));
  }

  /**
   * Search, filter and sort across every listed store (members and guests). Inputs arrive raw from
   * the query string and are validated here so every bad value answers INVALID_QUERY.
   */
  public PublicProductPage publicSearch(String text, UUID categoryId, UUID cityId, UUID storeId, Long minPrice,
      Long maxPrice, Boolean inStockOnly, String sort, Integer offset, Integer limit) {
    int size = limit == null ? ProductSearch.DEFAULT_LIMIT : limit;
    int from = offset == null ? 0 : offset;
    String normalized = ArabicText.normalize(text);
    ProductSort order;
    try {
      order = sort == null || sort.isBlank() ? ProductSort.NEWEST : ProductSort.valueOf(sort.strip());
    } catch (IllegalArgumentException e) {
      throw fail(Kind.INVALID, "INVALID_QUERY");
    }
    if (size < 1 || size > ProductSearch.MAX_LIMIT || from < 0 || from > ProductSearch.MAX_OFFSET
        || normalized.length() > ProductSearch.MAX_TEXT
        || (minPrice != null && minPrice < 0) || (maxPrice != null && maxPrice < 0)
        || (minPrice != null && maxPrice != null && minPrice > maxPrice))
      throw fail(Kind.INVALID, "INVALID_QUERY");
    var q = new ProductSearch(normalized, categoryId, cityId, storeId, minPrice, maxPrice,
        Boolean.TRUE.equals(inStockOnly), order, from, size);

    var stores = new LinkedHashMap<UUID, PartnerLookup.ListedStore>();
    for (var st : partners.listedStores())
      if ((cityId == null || st.cityId().equals(cityId)) && (storeId == null || st.id().equals(storeId)))
        stores.put(st.id(), st);
    if (stores.isEmpty()) return new PublicProductPage(0, from, size, List.of());
    var matching = normalized.isEmpty() ? List.<UUID>of() : stores.values().stream()
        .filter(st -> ArabicText.normalize(st.name()).contains(normalized)).map(PartnerLookup.ListedStore::id).toList();
    var ids = List.copyOf(stores.keySet());
    var page = repository.search(ids, ids.stream().map(id -> stores.get(id).minimumDiscountPercent()).toList(), q, matching);
    return new PublicProductPage(page.total(), from, size, publicViews(page.hits(), stores));
  }

  private List<PublicProductView> publicViews(List<SearchHit> hits, Map<UUID, PartnerLookup.ListedStore> stores) {
    var stored = repository.images(hits.stream().map(h -> h.product().id()).toList());
    var categories = categoryNames();
    return hits.stream().map(h -> {
      var p = h.product();
      var store = stores.get(p.storeId());
      return new PublicProductView(p.id(), p.name(), p.description(), categories.getOrDefault(p.categoryId(), ""),
          p.priceAmount(), h.memberPrice(), p.currency(), p.points(),
          p.stockTracked() && p.stock() != null && p.stock() == 0,
          stored.getOrDefault(p.id(), List.of()).stream().map(i -> images.publicUrl(i.objectKey())).toList(),
          p.storeId(), store.name(), store.cityName());
    }).toList();
  }

  public Optional<byte[]> localImage(String key) {
    return images.read(key);
  }

  // ---- shared with the review service ----

  ProductDraft validDraft(ProductRequest r, PartnerRef partner) {
    var category = repository.category(r.categoryId()).filter(Category::active)
        .orElseThrow(() -> fail(Kind.INVALID, "CATEGORY_NOT_FOUND"));
    String name = r.name().strip().replaceAll("\\s+", " ");
    if (name.isEmpty()) throw fail(Kind.INVALID, "INVALID_PRODUCT_NAME");
    String description = r.description() == null || r.description().isBlank() ? null : r.description().strip();
    long price = r.priceAmount();
    if (price <= 0 || price > MAX_PRICE) throw fail(Kind.INVALID, "INVALID_PRICE");
    if (r.memberPriceAmount() != null
        && !Rules.validCustomMemberPrice(r.memberPriceAmount(), price, partner.minimumDiscountPercent()))
      throw fail(Kind.INVALID, "MEMBER_PRICE_BELOW_MINIMUM_DISCOUNT");
    if (r.points() < Rules.minimumPoints(price)) throw fail(Kind.INVALID, "POINTS_BELOW_MINIMUM");
    boolean tracked = Boolean.TRUE.equals(r.stockTracked());
    Integer stock = tracked ? (r.stock() == null ? 0 : r.stock()) : null;
    return new ProductDraft(category.id(), name, description, price, r.memberPriceAmount(), r.points(), tracked, stock);
  }

  List<ProductView> views(List<Product> products, Map<UUID, StoreRef> stores, PartnerRef partner) {
    var stored = repository.images(products.stream().map(Product::id).toList());
    var categories = categoryNames();
    return products.stream().map(p -> view(p, stores.get(p.storeId()), partner, stored.getOrDefault(p.id(), List.of()), categories)).toList();
  }

  ProductView view(Product p, StoreRef store, PartnerRef partner) {
    return view(p, store, partner, repository.images(p.id()), categoryNames());
  }

  private ProductView view(Product p, StoreRef store, PartnerRef partner, List<ProductImage> stored, Map<UUID, String> categories) {
    var min = partner.minimumDiscountPercent();
    return new ProductView(p.id(), p.storeId(), store == null ? "" : store.name(), p.categoryId(),
        categories.getOrDefault(p.categoryId(), ""), p.name(), p.description(), p.priceAmount(), p.currency(),
        p.memberPriceAmount() != null, Rules.effectiveMemberPrice(p, min), Rules.minimumPoints(p.priceAmount()),
        p.points(), p.stockTracked(), p.stock(), p.reviewStatus(), p.reviewReason(), p.published(),
        Rules.visibleToMembers(p, store != null && store.active(), partner.active()), p.version(),
        stored.stream().map(i -> new ImageView(i.id(), i.position(), images.publicUrl(i.objectKey()))).toList(),
        p.updatedAt());
  }

  private Map<UUID, String> categoryNames() {
    var result = new HashMap<UUID, String>();
    repository.categories(false).forEach(c -> result.put(c.id(), c.name()));
    return result;
  }

  private void quietDelete(String key) {
    try { images.delete(key); } catch (RuntimeException ignored) { /* an orphan object is harmless; never fail the request */ }
  }

  static DomainException fail(Kind kind, String code) {
    return new DomainException(kind, code);
  }
}
