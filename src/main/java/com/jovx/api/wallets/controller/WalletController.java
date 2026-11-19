package com.jovx.api.wallets.controller;

import com.jovx.api.wallets.dto.WalletDtos.*;
import com.jovx.api.wallets.model.WalletModels.RequestStatus;
import com.jovx.api.wallets.service.WalletService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
public class WalletController {
  private static final String PARTNER_SIDE = "hasRole('PARTNER') or hasAuthority('wallet.read')";
  private static final String ADMIN = "hasRole('SUPER_ADMIN') or hasAuthority('wallet.manage')";
  private final WalletService wallets;

  public WalletController(WalletService wallets) {
    this.wallets = wallets;
  }

  // ---- owning partner and its employees ----

  @GetMapping("/api/v1/partner/wallet")
  @PreAuthorize(PARTNER_SIDE)
  public WalletView own(JwtAuthenticationToken a) {
    return wallets.ownWallet(id(a), sid(a), role(a));
  }

  @PostMapping("/api/v1/partner/wallet/topups")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PARTNER') or hasAuthority('wallet.topup.request')")
  public RequestView topup(JwtAuthenticationToken a, @Valid @RequestBody TopupRequest body) {
    return wallets.requestTopup(id(a), sid(a), role(a), body);
  }

  @PostMapping("/api/v1/partner/wallet/refunds")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('PARTNER')")
  public RequestView refund(JwtAuthenticationToken a, @Valid @RequestBody RefundRequest body) {
    return wallets.requestRefund(id(a), sid(a), role(a), body);
  }

  // ---- administration ----

  @GetMapping("/api/v1/admin/wallets")
  @PreAuthorize(ADMIN)
  public List<WalletSummary> summaries(JwtAuthenticationToken a) {
    return wallets.summaries(id(a), sid(a));
  }

  @GetMapping("/api/v1/admin/wallets/requests")
  @PreAuthorize(ADMIN)
  public List<RequestView> requests(JwtAuthenticationToken a, @RequestParam(required = false) RequestStatus status) {
    return wallets.requests(id(a), sid(a), status);
  }

  @GetMapping("/api/v1/admin/wallets/settings")
  @PreAuthorize(ADMIN)
  public SettingsView settings(JwtAuthenticationToken a) {
    return wallets.settings(id(a), sid(a));
  }

  @PutMapping("/api/v1/admin/wallets/settings")
  @PreAuthorize(ADMIN)
  public SettingsView updateSettings(JwtAuthenticationToken a, @Valid @RequestBody SettingsRequest body) {
    return wallets.updateSettings(id(a), sid(a), body);
  }

  @GetMapping("/api/v1/admin/wallets/{partnerId}")
  @PreAuthorize(ADMIN)
  public WalletView partner(JwtAuthenticationToken a, @PathVariable UUID partnerId) {
    return wallets.partnerWallet(id(a), sid(a), partnerId);
  }

  @PostMapping("/api/v1/admin/wallets/{partnerId}/topups")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize(ADMIN)
  public RequestView directTopup(JwtAuthenticationToken a, @PathVariable UUID partnerId, @Valid @RequestBody TopupRequest body) {
    return wallets.directTopup(id(a), sid(a), partnerId, body);
  }

  @PostMapping("/api/v1/admin/wallets/requests/{requestId}/approve")
  @PreAuthorize(ADMIN)
  public RequestView approve(JwtAuthenticationToken a, @PathVariable UUID requestId, @Valid @RequestBody(required = false) ApproveRequest body) {
    return wallets.approve(id(a), sid(a), requestId, body);
  }

  @PostMapping("/api/v1/admin/wallets/requests/{requestId}/reject")
  @PreAuthorize(ADMIN)
  public RequestView reject(JwtAuthenticationToken a, @PathVariable UUID requestId, @Valid @RequestBody RejectRequest body) {
    return wallets.reject(id(a), sid(a), requestId, body.reason());
  }

  private static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  private static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }

  private static String role(JwtAuthenticationToken a) {
    return a.getToken().getClaimAsString("role");
  }
}
