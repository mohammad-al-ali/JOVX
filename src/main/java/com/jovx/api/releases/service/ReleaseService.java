package com.jovx.api.releases.service;

import com.jovx.api.releases.dto.ReleaseDtos.*;
import com.jovx.api.releases.model.ReleaseModels;
import com.jovx.api.releases.model.ReleaseModels.*;
import com.jovx.api.releases.repository.ReleaseRepository;
import com.jovx.api.security.api.AdministrationAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Release lifecycle: import CI build as DRAFT, choose policy, publish or withdraw.
 * Administration only (SUPER_ADMIN, or ADMIN with app.release.manage).
 */
@Service
@Profile("local-postgres")
public class ReleaseService {
  static final String PERMISSION = "app.release.manage";
  private final ReleaseRepository repository;
  private final TransactionTemplate tx;
  private final AdministrationAccess access;
  private final ManifestClient manifests;
  private final Clock clock;

  public ReleaseService(
      ReleaseRepository repository,
      @Qualifier("releaseTransactions") TransactionTemplate tx,
      AdministrationAccess access,
      ManifestClient manifests,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.access = access;
    this.manifests = manifests;
    this.clock = clock;
  }

  /** Public: no account needed, a required update may block login itself. */
  public UpdateCheckResponse check(AppKind app, Platform platform, int currentBuild) {
    var decision =
        ReleaseModels.decide(
            currentBuild,
            repository.published(app, platform),
            repository.minSupported(app, platform),
            clock.instant());
    return UpdateCheckResponse.of(currentBuild, decision);
  }

  public ReleaseOverview overview(UUID actor, UUID sid, AppKind app, Platform platform) {
    return tx.execute(
        s -> {
          access.require(actor, sid, PERMISSION);
          return new ReleaseOverview(
              app,
              platform,
              repository.minSupported(app, platform),
              repository.releases(app, platform).stream().map(ReleaseView::of).toList());
        });
  }

  public ManifestView manifest(UUID actor, UUID sid, AppKind app, Platform platform) {
    tx.executeWithoutResult(s -> access.require(actor, sid, PERMISSION));
    // Network I/O stays outside any database transaction.
    var m = latestManifest(app, platform);
    return new ManifestView(
        m.versionName(),
        m.buildNumber(),
        m.sizeBytes(),
        m.notes(),
        m.sourceCommit(),
        repository.byBuild(app, platform, m.buildNumber()).isPresent());
  }

  /** Idempotent: importing the same build again returns the existing release. */
  public ReleaseView importLatest(UUID actor, UUID sid, AppKind app, Platform platform) {
    tx.executeWithoutResult(s -> access.require(actor, sid, PERMISSION));
    var m = latestManifest(app, platform);
    return tx.execute(
        s -> {
          access.require(actor, sid, PERMISSION);
          repository.insertDraft(UUID.randomUUID(), m, actor, clock.instant());
          var release = repository.byBuild(app, platform, m.buildNumber()).orElseThrow();
          repository.audit(release.id(), app, actor, "RELEASE_IMPORTED", clock.instant());
          return ReleaseView.of(release);
        });
  }

  public void policy(UUID actor, UUID sid, UUID id, UpdateMode mode, Instant requiredAfter) {
    tx.executeWithoutResult(
        s -> {
          access.require(actor, sid, PERMISSION);
          var release = lock(id);
          if (release.status() == ReleaseStatus.WITHDRAWN)
            throw error(Kind.CONFLICT, "RELEASE_WITHDRAWN");
          if (mode == UpdateMode.REQUIRED && requiredAfter != null)
            throw error(Kind.INVALID, "DEADLINE_ONLY_FOR_OPTIONAL");
          repository.updatePolicy(id, mode, requiredAfter);
          repository.audit(id, release.app(), actor, "RELEASE_POLICY_CHANGED", clock.instant());
        });
  }

  public void publish(UUID actor, UUID sid, UUID id) {
    transition(actor, sid, id, ReleaseStatus.PUBLISHED, "RELEASE_PUBLISHED");
  }

  /** Stops offering a faulty build at once; users already on it are not forced back. */
  public void withdraw(UUID actor, UUID sid, UUID id) {
    transition(actor, sid, id, ReleaseStatus.WITHDRAWN, "RELEASE_WITHDRAWN");
  }

  /** Builds below this number must update; it must not exceed the latest published build. */
  public void minSupported(UUID actor, UUID sid, AppKind app, Platform platform, int build) {
    tx.executeWithoutResult(
        s -> {
          access.require(actor, sid, PERMISSION);
          repository.lockMinSupported(app, platform);
          int latest =
              repository.published(app, platform).stream()
                  .mapToInt(AppRelease::buildNumber)
                  .max()
                  .orElse(0);
          if (build > latest) throw error(Kind.CONFLICT, "MIN_BUILD_NOT_PUBLISHED");
          repository.setMinSupported(app, platform, build, actor, clock.instant());
          repository.audit(null, app, actor, "MIN_SUPPORTED_CHANGED", clock.instant());
        });
  }

  private void transition(UUID actor, UUID sid, UUID id, ReleaseStatus to, String action) {
    tx.executeWithoutResult(
        s -> {
          access.require(actor, sid, PERMISSION);
          var release = lock(id);
          // Serialize with min-supported changes for the same app.
          repository.lockMinSupported(release.app(), release.platform());
          if (release.status() == to) return;
          if (release.status() == ReleaseStatus.WITHDRAWN)
            throw error(Kind.CONFLICT, "RELEASE_WITHDRAWN");
          if (to == ReleaseStatus.WITHDRAWN) {
            int remaining =
                repository.published(release.app(), release.platform()).stream()
                    .filter(r -> !r.id().equals(id))
                    .mapToInt(AppRelease::buildNumber)
                    .max()
                    .orElse(0);
            // Never leave a minimum that no published build can satisfy.
            if (repository.minSupported(release.app(), release.platform()) > remaining)
              throw error(Kind.CONFLICT, "LOWER_MIN_SUPPORTED_FIRST");
          }
          repository.setStatus(id, to, clock.instant());
          repository.audit(id, release.app(), actor, action, clock.instant());
        });
  }

  private ReleaseManifest latestManifest(AppKind app, Platform platform) {
    return manifests
        .latest(app, platform)
        .orElseThrow(() -> error(Kind.UNAVAILABLE, "MANIFEST_NOT_CONFIGURED"));
  }

  private AppRelease lock(UUID id) {
    return repository.lock(id).orElseThrow(() -> error(Kind.NOT_FOUND, "RELEASE_NOT_FOUND"));
  }

  private static DomainException error(Kind status, String code) {
    return new DomainException(status, code);
  }
}
