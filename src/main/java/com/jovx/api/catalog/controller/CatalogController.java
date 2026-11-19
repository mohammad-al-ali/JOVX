package com.jovx.api.catalog.controller;

import com.jovx.api.catalog.dto.CatalogDtos.*;
import com.jovx.api.catalog.service.CatalogService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local-postgres")
public class CatalogController {
  private static final String READ = "hasRole('PARTNER') or hasAuthority('catalog.read')";
  private static final String WRITE = "hasRole('PARTNER') or hasAuthority('catalog.submit')";
  private final CatalogService catalog;

  public CatalogController(CatalogService catalog) {
    this.catalog = catalog;
  }

  // ---- owning partner and assigned employees ----

  @GetMapping("/api/v1/partner/catalog/categories")
  @PreAuthorize(READ)
  public List<CategoryView> partnerCategories() {
    return catalog.activeCategories();
  }

  @GetMapping("/api/v1/partner/stores/{storeId}/products")
  @PreAuthorize(READ)
  public List<ProductView> products(JwtAuthenticationToken a, @PathVariable UUID storeId) {
    return catalog.storeProducts(id(a), sid(a), role(a), storeId);
  }

  @PostMapping("/api/v1/partner/stores/{storeId}/products")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(WRITE)
  public ProductView create(JwtAuthenticationToken a, @PathVariable UUID storeId, @Valid @RequestBody CreateProductRequest body) {
    return catalog.create(id(a), sid(a), role(a), storeId, body);
  }

  @PutMapping("/api/v1/partner/products/{productId}")
  @PreAuthorize(WRITE)
  public ProductView update(JwtAuthenticationToken a, @PathVariable UUID productId, @Valid @RequestBody UpdateProductRequest body) {
    return catalog.update(id(a), sid(a), role(a), productId, body);
  }

  @PutMapping("/api/v1/partner/products/{productId}/stock")
  @PreAuthorize(WRITE)
  public ProductView stock(JwtAuthenticationToken a, @PathVariable UUID productId, @Valid @RequestBody StockRequest body) {
    return catalog.setStock(id(a), sid(a), role(a), productId, body.stock());
  }

  @PutMapping("/api/v1/partner/products/{productId}/published")
  @PreAuthorize(WRITE)
  public ProductView published(JwtAuthenticationToken a, @PathVariable UUID productId, @Valid @RequestBody PublishedRequest body) {
    return catalog.setPublished(id(a), sid(a), role(a), productId, body.published());
  }

  @DeleteMapping("/api/v1/partner/products/{productId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(WRITE)
  public void delete(JwtAuthenticationToken a, @PathVariable UUID productId) {
    catalog.delete(id(a), sid(a), role(a), productId);
  }

  @PostMapping(path = "/api/v1/partner/products/{productId}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @PreAuthorize(WRITE)
  public ProductView addImage(JwtAuthenticationToken a, @PathVariable UUID productId, @RequestPart("file") MultipartFile file) {
    byte[] bytes;
    try { bytes = file.getBytes(); }
    catch (IOException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "IMAGE_UNREADABLE"); }
    return catalog.addImage(id(a), sid(a), role(a), productId, bytes);
  }

  @DeleteMapping("/api/v1/partner/products/{productId}/images/{imageId}")
  @PreAuthorize(WRITE)
  public ProductView removeImage(JwtAuthenticationToken a, @PathVariable UUID productId, @PathVariable UUID imageId) {
    return catalog.removeImage(id(a), sid(a), role(a), productId, imageId);
  }

  // ---- public: members and guests ----

  @GetMapping("/api/v1/public/product-categories")
  public List<CategoryView> publicCategories() {
    return catalog.activeCategories();
  }

  @GetMapping("/api/v1/public/stores/{storeId}/products")
  public List<PublicProductView> publicProducts(@PathVariable UUID storeId) {
    return catalog.publicStoreProducts(storeId);
  }

  /** Development storage only; online images are served by Cloudflare R2. */
  @GetMapping("/api/v1/public/images/products/{productId}/{file}")
  public ResponseEntity<byte[]> image(@PathVariable String productId, @PathVariable String file) {
    return catalog.localImage("products/" + productId + "/" + file)
        .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(365)).cachePublic()).body(bytes))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }

  static String role(JwtAuthenticationToken a) {
    return a.getToken().getClaimAsString("role");
  }
}
