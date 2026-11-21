package com.jovx.api.partners.controller;

import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.service.PartnerManagementService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Post-registration management of a partner's stores: administration paths and the owner's own. */
@RestController
@Profile("local-postgres")
public class PartnerManagementController {
  private static final String ADMIN_READ =
      "hasRole('SUPER_ADMIN') or hasAnyAuthority('partner.create','partner.store.manage')";
  private static final String ADMIN_CREATE = "hasRole('SUPER_ADMIN') or hasAuthority('partner.create')";
  private static final String ADMIN_STORES =
      "hasRole('SUPER_ADMIN') or hasAuthority('partner.store.manage')";
  private static final String ADMIN_PIN = "hasRole('SUPER_ADMIN') or hasAuthority('partner.pin.reset')";
  private static final String OWNER = "hasRole('PARTNER')";
  private final PartnerManagementService partners;

  public PartnerManagementController(PartnerManagementService partners) {
    this.partners = partners;
  }

  // ---- administration ----

  @GetMapping("/api/v1/admin/partners")
  @PreAuthorize(ADMIN_READ)
  public List<PartnerSummary> list(
      JwtAuthenticationToken actor,
      @RequestParam(required = false) @Size(max = 100) String q,
      @RequestParam(defaultValue = "0") @Min(0) int offset,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
    return partners.list(id(actor), sid(actor), q, offset, limit);
  }

  @GetMapping("/api/v1/admin/partners/{partnerId}")
  @PreAuthorize(ADMIN_READ)
  public PartnerDetail detail(JwtAuthenticationToken actor, @PathVariable UUID partnerId) {
    return partners.detail(id(actor), sid(actor), partnerId);
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/discount")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_CREATE)
  public void discount(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @Valid @RequestBody DiscountRequest body) {
    partners.discount(id(actor), sid(actor), partnerId, body.discountPercent());
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/pin")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_PIN)
  public void resetPin(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @Valid @RequestBody EmployeePinRequest body) {
    partners.resetPin(id(actor), sid(actor), partnerId, body.pin());
  }

  @PostMapping("/api/v1/admin/partners/{partnerId}/stores")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(ADMIN_STORES)
  public StoreCreatedResponse addStore(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @Valid @RequestBody StoreRequest body) {
    return partners.adminAddStore(id(actor), sid(actor), partnerId, body);
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/stores/{storeId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_STORES)
  public void editStore(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID storeId,
      @Valid @RequestBody StoreRequest body) {
    partners.adminEditStore(id(actor), sid(actor), partnerId, storeId, body);
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/stores/{storeId}/status")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_STORES)
  public void storeStatus(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID storeId,
      @Valid @RequestBody StatusRequest body) {
    partners.adminStoreStatus(id(actor), sid(actor), partnerId, storeId, body.status());
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/stores/{storeId}/hours")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_STORES)
  public void storeHours(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID storeId,
      @Valid @RequestBody StoreHoursRequest body) {
    partners.adminStoreHours(id(actor), sid(actor), partnerId, storeId, body);
  }

  // ---- owning partner ----

  @GetMapping("/api/v1/partner/profile")
  @PreAuthorize(OWNER)
  public PartnerDetail profile(JwtAuthenticationToken actor) {
    return partners.profile(id(actor), sid(actor));
  }

  @PostMapping("/api/v1/partner/stores")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(OWNER)
  public StoreCreatedResponse ownAddStore(
      JwtAuthenticationToken actor, @Valid @RequestBody StoreRequest body) {
    return partners.ownAddStore(id(actor), sid(actor), body);
  }

  @PutMapping("/api/v1/partner/stores/{storeId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(OWNER)
  public void ownEditStore(
      JwtAuthenticationToken actor,
      @PathVariable UUID storeId,
      @Valid @RequestBody StoreRequest body) {
    partners.ownEditStore(id(actor), sid(actor), storeId, body);
  }

  @PutMapping("/api/v1/partner/stores/{storeId}/status")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(OWNER)
  public void ownStoreStatus(
      JwtAuthenticationToken actor,
      @PathVariable UUID storeId,
      @Valid @RequestBody StatusRequest body) {
    partners.ownStoreStatus(id(actor), sid(actor), storeId, body.status());
  }

  @PutMapping("/api/v1/partner/stores/{storeId}/hours")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(OWNER)
  public void ownStoreHours(
      JwtAuthenticationToken actor,
      @PathVariable UUID storeId,
      @Valid @RequestBody StoreHoursRequest body) {
    partners.ownStoreHours(id(actor), sid(actor), storeId, body);
  }

  private static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  private static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }
}
