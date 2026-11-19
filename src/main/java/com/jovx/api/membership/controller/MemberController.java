package com.jovx.api.membership.controller;

import static com.jovx.api.membership.controller.MembershipAdminController.id;
import static com.jovx.api.membership.controller.MembershipAdminController.sid;

import com.jovx.api.membership.dto.MembershipDtos.*;
import com.jovx.api.membership.service.MemberSelfService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
public class MemberController {
  private final MemberSelfService service;
  public MemberController(MemberSelfService service) {
    this.service = service;
  }

  /** Public and rate limited: sets the first PIN with the physical card (decision 44). */
  @PostMapping("/api/v1/member/claim")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void claim(@Valid @RequestBody ClaimRequest body) {
    service.claim(body);
  }

  /** Public: guests see what they can subscribe to. */
  @GetMapping("/api/v1/public/membership-plans")
  public List<PlanView> publicPlans() {
    return service.publicPlans();
  }

  @GetMapping("/api/v1/member/me")
  @PreAuthorize("hasRole('MEMBER')")
  public MemberProfile me(JwtAuthenticationToken a) {
    return service.profile(id(a), sid(a));
  }

  @PostMapping("/api/v1/member/scan-token")
  @PreAuthorize("hasRole('MEMBER')")
  public ScanToken scanToken(JwtAuthenticationToken a) {
    return service.scanToken(id(a), sid(a));
  }
}
