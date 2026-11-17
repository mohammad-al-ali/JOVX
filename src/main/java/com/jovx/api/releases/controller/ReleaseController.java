package com.jovx.api.releases.controller;

import com.jovx.api.releases.dto.ReleaseDtos.*;
import com.jovx.api.releases.model.ReleaseModels.*;
import com.jovx.api.releases.service.ReleaseService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("local-postgres")
public class ReleaseController {
  private static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('app.release.manage')";
  private final ReleaseService releases;

  public ReleaseController(ReleaseService releases) {
    this.releases = releases;
  }

  /** Public update check used at app start and from Settings. */
  @GetMapping("/api/v1/app-updates")
  public ResponseEntity<UpdateCheckResponse> check(
      @RequestParam String app, @RequestParam String platform, @RequestParam int build) {
    if (build < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_BUILD");
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(releases.check(app(app), platform(platform), build));
  }

  @GetMapping("/api/v1/admin/app-releases/{app}")
  @PreAuthorize(MANAGE)
  public ReleaseOverview overview(JwtAuthenticationToken actor, @PathVariable String app) {
    return releases.overview(id(actor), sid(actor), app(app), Platform.ANDROID);
  }

  @GetMapping("/api/v1/admin/app-releases/{app}/manifest")
  @PreAuthorize(MANAGE)
  public ManifestView manifest(JwtAuthenticationToken actor, @PathVariable String app) {
    return releases.manifest(id(actor), sid(actor), app(app), Platform.ANDROID);
  }

  @PostMapping("/api/v1/admin/app-releases/{app}/import")
  @PreAuthorize(MANAGE)
  public ReleaseView importLatest(JwtAuthenticationToken actor, @PathVariable String app) {
    return releases.importLatest(id(actor), sid(actor), app(app), Platform.ANDROID);
  }

  @PutMapping("/api/v1/admin/app-releases/{app}/min-supported")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void minSupported(
      JwtAuthenticationToken actor,
      @PathVariable String app,
      @Valid @RequestBody MinSupportedRequest body) {
    releases.minSupported(id(actor), sid(actor), app(app), Platform.ANDROID, body.minSupportedBuild());
  }

  @PutMapping("/api/v1/admin/app-releases/releases/{releaseId}/policy")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void policy(
      JwtAuthenticationToken actor,
      @PathVariable UUID releaseId,
      @Valid @RequestBody PolicyRequest body) {
    releases.policy(id(actor), sid(actor), releaseId, body.mode(), body.requiredAfter());
  }

  @PostMapping("/api/v1/admin/app-releases/releases/{releaseId}/publish")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void publish(JwtAuthenticationToken actor, @PathVariable UUID releaseId) {
    releases.publish(id(actor), sid(actor), releaseId);
  }

  @PostMapping("/api/v1/admin/app-releases/releases/{releaseId}/withdraw")
  @PreAuthorize(MANAGE)
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void withdraw(JwtAuthenticationToken actor, @PathVariable UUID releaseId) {
    releases.withdraw(id(actor), sid(actor), releaseId);
  }

  private static AppKind app(String value) {
    return AppKind.parse(value)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_APP"));
  }

  private static Platform platform(String value) {
    return Platform.parse(value)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_PLATFORM"));
  }

  private static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  private static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }
}
