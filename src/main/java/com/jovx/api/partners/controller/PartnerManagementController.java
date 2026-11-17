package com.jovx.api.partners.controller;

import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.service.PartnerManagementService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Post-registration management: administration paths and the owning Partner's own paths. */
@RestController
@Profile("local-postgres")
public class PartnerManagementController {
  private static final String ADMIN_READ =
      "hasRole('SUPER_ADMIN') or hasAnyAuthority('partner.create','partner.branch.manage')";
  private static final String ADMIN_CREATE = "hasRole('SUPER_ADMIN') or hasAuthority('partner.create')";
  private static final String ADMIN_BRANCHES =
      "hasRole('SUPER_ADMIN') or hasAuthority('partner.branch.manage')";
  private final PartnerManagementService partners;

  public PartnerManagementController(PartnerManagementService partners) {
    this.partners = partners;
  }

  // ---- administration ----

  @GetMapping("/api/v1/admin/partners")
  @PreAuthorize(ADMIN_READ)
  public List<PartnerSummary> list(JwtAuthenticationToken actor) {
    return partners.list(id(actor), sid(actor));
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

  @PostMapping("/api/v1/admin/partners/{partnerId}/establishments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(ADMIN_CREATE)
  public EstablishmentCreatedResponse addEstablishment(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @Valid @RequestBody NewEstablishmentRequest body) {
    return partners.adminAddEstablishment(id(actor), sid(actor), partnerId, body);
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/establishments/{establishmentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_CREATE)
  public void editEstablishment(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID establishmentId,
      @Valid @RequestBody EstablishmentRequest body) {
    partners.adminEditEstablishment(id(actor), sid(actor), partnerId, establishmentId, body);
  }

  @PostMapping("/api/v1/admin/partners/{partnerId}/establishments/{establishmentId}/branches")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(ADMIN_BRANCHES)
  public BranchCreatedResponse addBranch(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID establishmentId,
      @Valid @RequestBody BranchRequest body) {
    return partners.adminAddBranch(id(actor), sid(actor), partnerId, establishmentId, body);
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/branches/{branchId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_BRANCHES)
  public void editBranch(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID branchId,
      @Valid @RequestBody BranchRequest body) {
    partners.adminEditBranch(id(actor), sid(actor), partnerId, branchId, body);
  }

  @PutMapping("/api/v1/admin/partners/{partnerId}/branches/{branchId}/status")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(ADMIN_BRANCHES)
  public void branchStatus(
      JwtAuthenticationToken actor,
      @PathVariable UUID partnerId,
      @PathVariable UUID branchId,
      @Valid @RequestBody StatusRequest body) {
    partners.adminBranchStatus(id(actor), sid(actor), partnerId, branchId, body.status());
  }

  // ---- owning partner ----

  @GetMapping("/api/v1/partner/profile")
  @PreAuthorize("hasRole('PARTNER')")
  public PartnerDetail profile(JwtAuthenticationToken actor) {
    return partners.profile(id(actor), sid(actor));
  }

  @PostMapping("/api/v1/partner/establishments")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PARTNER')")
  public EstablishmentCreatedResponse ownAddEstablishment(
      JwtAuthenticationToken actor, @Valid @RequestBody NewEstablishmentRequest body) {
    return partners.ownAddEstablishment(id(actor), sid(actor), body);
  }

  @PutMapping("/api/v1/partner/establishments/{establishmentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PARTNER')")
  public void ownEditEstablishment(
      JwtAuthenticationToken actor,
      @PathVariable UUID establishmentId,
      @Valid @RequestBody EstablishmentRequest body) {
    partners.ownEditEstablishment(id(actor), sid(actor), establishmentId, body);
  }

  @PostMapping("/api/v1/partner/establishments/{establishmentId}/branches")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PARTNER')")
  public BranchCreatedResponse ownAddBranch(
      JwtAuthenticationToken actor,
      @PathVariable UUID establishmentId,
      @Valid @RequestBody BranchRequest body) {
    return partners.ownAddBranch(id(actor), sid(actor), establishmentId, body);
  }

  @PutMapping("/api/v1/partner/branches/{branchId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PARTNER')")
  public void ownEditBranch(
      JwtAuthenticationToken actor,
      @PathVariable UUID branchId,
      @Valid @RequestBody BranchRequest body) {
    partners.ownEditBranch(id(actor), sid(actor), branchId, body);
  }

  @PutMapping("/api/v1/partner/branches/{branchId}/status")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('PARTNER')")
  public void ownBranchStatus(
      JwtAuthenticationToken actor,
      @PathVariable UUID branchId,
      @Valid @RequestBody StatusRequest body) {
    partners.ownBranchStatus(id(actor), sid(actor), branchId, body.status());
  }

  private static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  private static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }
}
