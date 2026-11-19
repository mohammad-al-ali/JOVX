package com.jovx.api.membership.controller;

import com.jovx.api.membership.dto.MembershipDtos.*;
import com.jovx.api.membership.model.MembershipModels.CardStatus;
import com.jovx.api.membership.service.MembershipAdminService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Administration: plans, printed-card stock, membership sale and renewal, card suspension, PIN reset. */
@RestController
@Profile("local-postgres")
@PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('member.manage')")
public class MembershipAdminController {
  private final MembershipAdminService service;

  public MembershipAdminController(MembershipAdminService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/admin/membership-plans")
  public List<PlanView> plans(JwtAuthenticationToken a) {
    return service.plans(id(a), sid(a));
  }

  @PostMapping("/api/v1/admin/membership-plans")
  @ResponseStatus(HttpStatus.CREATED)
  public PlanView createPlan(JwtAuthenticationToken a, @Valid @RequestBody PlanRequest body) {
    return service.createPlan(id(a), sid(a), body);
  }

  @PutMapping("/api/v1/admin/membership-plans/{planId}")
  public PlanView updatePlan(JwtAuthenticationToken a, @PathVariable UUID planId, @Valid @RequestBody PlanRequest body) {
    return service.updatePlan(id(a), sid(a), planId, body);
  }

  @GetMapping("/api/v1/admin/member-cards")
  public CardStock cards(
      JwtAuthenticationToken a,
      @RequestParam(required = false) CardStatus status,
      @RequestParam(required = false) String q) {
    return service.cards(id(a), sid(a), status, q);
  }

  @PostMapping("/api/v1/admin/member-cards/batches")
  public CardBatchResult registerCards(JwtAuthenticationToken a, @Valid @RequestBody CardBatchRequest body) {
    return service.registerCards(id(a), sid(a), body);
  }

  @GetMapping("/api/v1/admin/members")
  public List<MemberSummary> members(JwtAuthenticationToken a, @RequestParam(required = false) String q) {
    return service.search(id(a), sid(a), q);
  }

  @PostMapping("/api/v1/admin/members")
  public MembershipTermResult sell(JwtAuthenticationToken a, @Valid @RequestBody SellMembershipRequest body) {
    return service.sell(id(a), sid(a), body);
  }

  @GetMapping("/api/v1/admin/members/{memberId}")
  public MemberDetail member(JwtAuthenticationToken a, @PathVariable UUID memberId) {
    return service.detail(id(a), sid(a), memberId);
  }

  @PostMapping("/api/v1/admin/members/{memberId}/renewals")
  public MembershipTermResult renew(
      JwtAuthenticationToken a, @PathVariable UUID memberId, @Valid @RequestBody RenewMembershipRequest body) {
    return service.renew(id(a), sid(a), memberId, body);
  }

  @PostMapping("/api/v1/admin/members/{memberId}/card/suspension")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void suspendCard(JwtAuthenticationToken a, @PathVariable UUID memberId) {
    service.suspendCard(id(a), sid(a), memberId);
  }

  @PutMapping("/api/v1/admin/members/{memberId}/pin")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPin(JwtAuthenticationToken a, @PathVariable UUID memberId, @Valid @RequestBody ResetPinRequest body) {
    service.resetPin(id(a), sid(a), memberId, body.pin());
  }

  static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }
}
