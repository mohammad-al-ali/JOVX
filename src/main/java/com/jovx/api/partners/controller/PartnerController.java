package com.jovx.api.partners.controller;

import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.model.PartnerModels.Address;
import com.jovx.api.partners.model.PartnerModels.Reference;
import com.jovx.api.partners.service.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
public class PartnerController {
  private final PartnerService registration;

  public PartnerController(PartnerService registration) {
    this.registration = registration;
  }

  @PostMapping("/api/v1/admin/partners")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('partner.create')")
  public PartnerCreatedResponse create(
      JwtAuthenticationToken actor, @Valid @RequestBody RegisterPartnerRequest command) {
    return registration.create(UUID.fromString(actor.getName()), sid(actor), command);
  }

  @PostMapping("/api/v1/admin/partners/{id}/activate")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('partner.create')")
  public void activate(JwtAuthenticationToken actor, @PathVariable UUID id) {
    registration.activate(UUID.fromString(actor.getName()), sid(actor), id);
  }

  @GetMapping("/api/v1/partner/context")
  @PreAuthorize("hasRole('PARTNER')")
  public PartnerContextResponse context(JwtAuthenticationToken actor) {
    return registration.context(UUID.fromString(actor.getName()));
  }

  @GetMapping("/api/v1/reference-data")
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasRole('PARTNER') or hasAuthority('partner.create')")
  public ReferenceDataResponse referenceData() {
    return registration.referenceData();
  }

  @PostMapping("/api/v1/reference-data/cities")
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('partner.create')")
  public Reference city(
      JwtAuthenticationToken actor, @Valid @RequestBody ReferenceRequest command) {
    return registration.city(UUID.fromString(actor.getName()), sid(actor), command.name());
  }

  @PostMapping("/api/v1/reference-data/categories")
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('partner.create')")
  public Reference category(
      JwtAuthenticationToken actor, @Valid @RequestBody ReferenceRequest command) {
    return registration.category(UUID.fromString(actor.getName()), sid(actor), command.name());
  }

  @PostMapping("/api/v1/reference-data/addresses")
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('partner.create')")
  public Address address(JwtAuthenticationToken actor, @Valid @RequestBody AddressRequest command) {
    return registration.address(
        UUID.fromString(actor.getName()), sid(actor), command.cityId(), command.name());
  }

  private static UUID sid(JwtAuthenticationToken actor) {
    return UUID.fromString(actor.getToken().getClaimAsString("sid"));
  }
}
