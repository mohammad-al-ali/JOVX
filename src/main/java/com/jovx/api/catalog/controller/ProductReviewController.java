package com.jovx.api.catalog.controller;

import static com.jovx.api.catalog.controller.CatalogController.id;
import static com.jovx.api.catalog.controller.CatalogController.sid;

import com.jovx.api.catalog.dto.CatalogDtos.*;
import com.jovx.api.catalog.model.CatalogModels.ReviewStatus;
import com.jovx.api.catalog.service.ProductReviewService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
public class ProductReviewController {
  private static final String REVIEW = "hasRole('SUPER_ADMIN') or hasAuthority('catalog.review')";
  private final ProductReviewService review;

  public ProductReviewController(ProductReviewService review) {
    this.review = review;
  }

  @GetMapping("/api/v1/admin/catalog/products")
  @PreAuthorize(REVIEW)
  public List<ProductView> queue(JwtAuthenticationToken a, @RequestParam(required = false) ReviewStatus status) {
    return review.queue(id(a), sid(a), status);
  }

  @PostMapping("/api/v1/admin/catalog/products/{productId}/approve")
  @PreAuthorize(REVIEW)
  public ProductView approve(JwtAuthenticationToken a, @PathVariable UUID productId) {
    return review.approve(id(a), sid(a), productId);
  }

  @PostMapping("/api/v1/admin/catalog/products/{productId}/reject")
  @PreAuthorize(REVIEW)
  public ProductView reject(JwtAuthenticationToken a, @PathVariable UUID productId, @Valid @RequestBody ReasonRequest body) {
    return review.reject(id(a), sid(a), productId, body.reason());
  }

  @PostMapping("/api/v1/admin/catalog/products/{productId}/hide")
  @PreAuthorize(REVIEW)
  public ProductView hide(JwtAuthenticationToken a, @PathVariable UUID productId, @Valid @RequestBody ReasonRequest body) {
    return review.hide(id(a), sid(a), productId, body.reason());
  }

  @GetMapping("/api/v1/admin/catalog/categories")
  @PreAuthorize(REVIEW)
  public List<CategoryView> categories(JwtAuthenticationToken a) {
    return review.categories(id(a), sid(a));
  }

  @PostMapping("/api/v1/admin/catalog/categories")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(REVIEW)
  public CategoryView createCategory(JwtAuthenticationToken a, @Valid @RequestBody CategoryRequest body) {
    return review.saveCategory(id(a), sid(a), null, body);
  }

  @PutMapping("/api/v1/admin/catalog/categories/{categoryId}")
  @PreAuthorize(REVIEW)
  public CategoryView updateCategory(JwtAuthenticationToken a, @PathVariable UUID categoryId, @Valid @RequestBody CategoryRequest body) {
    return review.saveCategory(id(a), sid(a), categoryId, body);
  }
}
