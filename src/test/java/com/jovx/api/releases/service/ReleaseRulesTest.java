package com.jovx.api.releases.service;

import static org.junit.jupiter.api.Assertions.*;

import com.jovx.api.releases.model.ReleaseModels;
import com.jovx.api.releases.model.ReleaseModels.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class ReleaseRulesTest {
  static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

  static AppRelease release(int build, ReleaseStatus status, UpdateMode mode, Instant deadline) {
    return new AppRelease(
        UUID.randomUUID(), AppKind.MEMBER, Platform.ANDROID, "1." + build, build,
        "https://x/a.apk", "a".repeat(64), 10, null, null, status, mode, deadline, NOW, NOW);
  }

  @Test
  void noNewerPublishedBuildMeansNoUpdateEvenBelowMinimum() {
    var d = ReleaseModels.decide(5, List.of(release(9, ReleaseStatus.DRAFT, UpdateMode.REQUIRED, null)), 7, NOW);
    assertEquals(UpdateAction.NONE, d.action());
    assertNull(d.latest());
  }

  @Test
  void optionalOffersTheLatestPublishedBuild() {
    var d = ReleaseModels.decide(5, List.of(
        release(6, ReleaseStatus.PUBLISHED, UpdateMode.OPTIONAL, null),
        release(8, ReleaseStatus.PUBLISHED, UpdateMode.OPTIONAL, null),
        release(9, ReleaseStatus.WITHDRAWN, UpdateMode.OPTIONAL, null)), 0, NOW);
    assertEquals(UpdateAction.OPTIONAL, d.action());
    assertEquals(8, d.latest().buildNumber());
  }

  @Test
  void anySkippedRequiredReleaseMakesTheUpdateRequired() {
    var d = ReleaseModels.decide(5, List.of(
        release(6, ReleaseStatus.PUBLISHED, UpdateMode.REQUIRED, null),
        release(8, ReleaseStatus.PUBLISHED, UpdateMode.OPTIONAL, null)), 0, NOW);
    assertEquals(UpdateAction.REQUIRED, d.action());
    assertEquals(8, d.latest().buildNumber());
  }

  @Test
  void deadlineKeepsOptionalUntilItPassesThenRequires() {
    var deadline = NOW.plus(Duration.ofDays(3));
    var releases = List.of(release(6, ReleaseStatus.PUBLISHED, UpdateMode.OPTIONAL, deadline));
    var before = ReleaseModels.decide(5, releases, 0, NOW);
    assertEquals(UpdateAction.OPTIONAL, before.action());
    assertEquals(deadline, before.requiredAfter());
    assertEquals(UpdateAction.REQUIRED, ReleaseModels.decide(5, releases, 0, deadline).action());
  }

  @Test
  void belowMinimumSupportedIsRequired() {
    var releases = List.of(release(8, ReleaseStatus.PUBLISHED, UpdateMode.OPTIONAL, null));
    assertEquals(UpdateAction.REQUIRED, ReleaseModels.decide(5, releases, 6, NOW).action());
    assertEquals(UpdateAction.OPTIONAL, ReleaseModels.decide(6, releases, 6, NOW).action());
    assertEquals(UpdateAction.NONE, ReleaseModels.decide(8, releases, 6, NOW).action());
  }

  static final String PREFIX = "https://github.com/o/jovx-releases/releases/download/";
  final HttpManifestClient client = new HttpManifestClient("https://o.github.io/jovx-releases", PREFIX);

  ReleaseManifest parse(String json) {
    return client.parse(json.getBytes(StandardCharsets.UTF_8), AppKind.MEMBER, Platform.ANDROID);
  }

  String manifest(String app, String url, String sha) {
    return """
        {"app":"%s","platform":"android","versionName":"0.18.0+1801","buildNumber":1801,
         "url":"%s","sha256":"%s","sizeBytes":123456,"notes":"إصلاحات","commit":"abc1234"}
        """.formatted(app, url, sha);
  }

  @Test
  void validManifestIsAccepted() {
    var m = parse(manifest("member", PREFIX + "member-1801/app.apk", "b".repeat(64)));
    assertEquals(1801, m.buildNumber());
    assertEquals("إصلاحات", m.notes());
  }

  @Test
  void manifestCannotRedirectDownloadsOrLieAboutItself() {
    assertThrows(ResponseStatusException.class,
        () -> parse(manifest("member", "https://evil.example/app.apk", "b".repeat(64))));
    assertThrows(ResponseStatusException.class,
        () -> parse(manifest("admin", PREFIX + "x.apk", "b".repeat(64))));
    assertThrows(ResponseStatusException.class,
        () -> parse(manifest("member", PREFIX + "x.apk", "XYZ")));
    assertThrows(ResponseStatusException.class, () -> parse("not json"));
  }

  @Test
  void manifestSourceMustBeHttpsOutsideLoopback() {
    assertThrows(IllegalStateException.class, () -> new HttpManifestClient("http://example.com", PREFIX));
    assertDoesNotThrow(() -> new HttpManifestClient("http://127.0.0.1:9999", "http://127.0.0.1:9999/"));
    assertTrue(new HttpManifestClient("", "").latest(AppKind.MEMBER, Platform.ANDROID).isEmpty());
  }
}
