package com.jovx.api.partners.controller;

import com.jovx.api.partners.dto.PartnerDtos.LocationDefaultsRequest;
import com.jovx.api.partners.dto.PartnerDtos.LocationDefaultsView;
import com.jovx.api.partners.service.ReferenceDefaultsService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Reading the default location is public (guests filter the directory); changing it is administration. */
@RestController
@Profile("local-postgres")
public class ReferenceDefaultsController {
  private final ReferenceDefaultsService defaults;

  public ReferenceDefaultsController(ReferenceDefaultsService defaults) {
    this.defaults = defaults;
  }

  /** 204 while no default has been chosen yet. Ids and names only; no actor or audit data. */
  @GetMapping("/api/v1/public/location-defaults")
  public ResponseEntity<LocationDefaultsView> publicDefaults() {
    return defaults
        .current()
        .map(LocationDefaultsView::of)
        .map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PutMapping("/api/v1/admin/reference-defaults")
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('partner.create')")
  public LocationDefaultsView change(
      JwtAuthenticationToken actor, @Valid @RequestBody LocationDefaultsRequest body) {
    return LocationDefaultsView.of(
        defaults.change(
            UUID.fromString(actor.getName()),
            UUID.fromString(actor.getToken().getClaimAsString("sid")),
            body.cityId(),
            body.addressId()));
  }
}
