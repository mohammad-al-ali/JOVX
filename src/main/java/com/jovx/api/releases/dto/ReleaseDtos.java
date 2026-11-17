package com.jovx.api.releases.dto;

import com.jovx.api.releases.model.ReleaseModels.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

public final class ReleaseDtos {
  private ReleaseDtos() {}

  /** Public answer to "is there an update for my build?". */
  public record UpdateCheckResponse(
      UpdateAction action,
      int currentBuild,
      int minSupportedBuild,
      String latestVersionName,
      Integer latestBuildNumber,
      String downloadUrl,
      String sha256,
      Long sizeBytes,
      String notes,
      Instant requiredAfter) {

    public static UpdateCheckResponse of(int current, UpdateDecision d) {
      var r = d.latest();
      return new UpdateCheckResponse(
          d.action(),
          current,
          d.minSupportedBuild(),
          r == null ? null : r.versionName(),
          r == null ? null : r.buildNumber(),
          r == null ? null : r.downloadUrl(),
          r == null ? null : r.sha256(),
          r == null ? null : r.sizeBytes(),
          r == null ? null : r.notes(),
          d.requiredAfter());
    }
  }

  public record ReleaseView(
      UUID id,
      AppKind app,
      Platform platform,
      String versionName,
      int buildNumber,
      String downloadUrl,
      String sha256,
      long sizeBytes,
      String notes,
      String sourceCommit,
      ReleaseStatus status,
      UpdateMode mode,
      Instant requiredAfter,
      Instant createdAt,
      Instant publishedAt) {

    public static ReleaseView of(AppRelease r) {
      return new ReleaseView(
          r.id(), r.app(), r.platform(), r.versionName(), r.buildNumber(), r.downloadUrl(),
          r.sha256(), r.sizeBytes(), r.notes(), r.sourceCommit(), r.status(), r.mode(),
          r.requiredAfter(), r.createdAt(), r.publishedAt());
    }
  }

  public record ReleaseOverview(
      AppKind app,
      Platform platform,
      int minSupportedBuild,
      java.util.List<ReleaseView> releases) {}

  /** Latest CI build from version.json and whether it is already imported. */
  public record ManifestView(
      String versionName,
      int buildNumber,
      long sizeBytes,
      String notes,
      String sourceCommit,
      boolean imported) {}

  /** requiredAfter: optional deadline after which an OPTIONAL update becomes required. */
  public record PolicyRequest(@NotNull UpdateMode mode, Instant requiredAfter) {}

  public record MinSupportedRequest(@NotNull @Min(0) Integer minSupportedBuild) {}
}
