package com.jovx.api.releases.model;

import java.time.Instant;
import java.util.*;

public final class ReleaseModels {
  private ReleaseModels() {}

  public enum AppKind {
    ADMIN,
    MEMBER;

    /** Path/query form: "admin" or "member". */
    public static Optional<AppKind> parse(String value) {
      if (value == null) return Optional.empty();
      return Arrays.stream(values())
          .filter(a -> a.name().equalsIgnoreCase(value.strip()))
          .findFirst();
    }
  }

  public enum Platform {
    ANDROID;

    public static Optional<Platform> parse(String value) {
      if (value == null) return Optional.empty();
      return Arrays.stream(values())
          .filter(p -> p.name().equalsIgnoreCase(value.strip()))
          .findFirst();
    }
  }

  public enum ReleaseStatus {
    DRAFT,
    PUBLISHED,
    WITHDRAWN
  }

  public enum UpdateMode {
    OPTIONAL,
    REQUIRED
  }

  public enum UpdateAction {
    NONE,
    OPTIONAL,
    REQUIRED
  }

  public record AppRelease(
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
      Instant publishedAt) {}

  /** Build facts published by CI in version.json. */
  public record ReleaseManifest(
      AppKind app,
      Platform platform,
      String versionName,
      int buildNumber,
      String downloadUrl,
      String sha256,
      long sizeBytes,
      String notes,
      String sourceCommit) {}

  public record UpdateDecision(
      UpdateAction action, AppRelease latest, Instant requiredAfter, int minSupportedBuild) {}

  /**
   * Pure update rule. Only PUBLISHED releases newer than the current build count.
   *
   * <ul>
   *   <li>REQUIRED when the current build is below the minimum supported build, any newer
   *       release is REQUIRED, or a newer release's deadline has passed.
   *   <li>OPTIONAL otherwise, carrying the earliest future deadline, if any.
   *   <li>NONE when nothing newer is published (an update cannot be demanded without one).
   * </ul>
   */
  public static UpdateDecision decide(
      int currentBuild, List<AppRelease> releases, int minSupportedBuild, Instant now) {
    var newer =
        releases.stream()
            .filter(r -> r.status() == ReleaseStatus.PUBLISHED && r.buildNumber() > currentBuild)
            .toList();
    if (newer.isEmpty()) return new UpdateDecision(UpdateAction.NONE, null, null, minSupportedBuild);
    var latest = newer.stream().max(Comparator.comparingInt(AppRelease::buildNumber)).get();
    boolean required =
        currentBuild < minSupportedBuild
            || newer.stream()
                .anyMatch(
                    r ->
                        r.mode() == UpdateMode.REQUIRED
                            || (r.requiredAfter() != null && !now.isBefore(r.requiredAfter())));
    Instant deadline =
        required
            ? null
            : newer.stream()
                .map(AppRelease::requiredAfter)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    return new UpdateDecision(
        required ? UpdateAction.REQUIRED : UpdateAction.OPTIONAL,
        latest,
        deadline,
        minSupportedBuild);
  }
}
