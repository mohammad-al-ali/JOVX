package com.jovx.api.releases.service;

import com.jovx.api.releases.model.ReleaseModels.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fetches {base}/{app}/{platform}/version.json from the single configured source.
 *
 * <p>The URL is never taken from a request (no SSRF). The download URL inside the manifest must
 * stay under the configured download prefix (same scheme, host and port, and a path below the
 * prefix directory), so a tampered manifest cannot point apps elsewhere. Redirects are not
 * followed, so the manifest is always read from the configured host. HTTPS is required except for
 * loopback test servers.
 */
public final class HttpManifestClient implements ManifestClient {
  private static final int MAX_BYTES = 16 * 1024;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final String base;
  private final String downloadPrefix;
  private final HttpClient http =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(5))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();

  public HttpManifestClient(String base, String downloadPrefix) {
    this.base = blank(base) ? null : requireSafe(base.replaceAll("/+$", ""));
    // A directory prefix always ends in '/', so ".../jovx-releases" cannot match ".../jovx-releases-evil".
    this.downloadPrefix =
        blank(downloadPrefix) ? null : requireSafe(downloadPrefix.strip().replaceAll("/+$", "") + "/");
  }

  @Override
  public Optional<ReleaseManifest> latest(AppKind app, Platform platform) {
    if (base == null || downloadPrefix == null) return Optional.empty();
    var uri =
        URI.create(
            base
                + "/"
                + app.name().toLowerCase(Locale.ROOT)
                + "/"
                + platform.name().toLowerCase(Locale.ROOT)
                + "/version.json");
    final byte[] body;
    try {
      var response =
          http.send(
              HttpRequest.newBuilder(uri)
                  .timeout(Duration.ofSeconds(10))
                  .header("Accept", "application/json")
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofInputStream());
      try (var in = response.body()) {
        body = in.readNBytes(MAX_BYTES + 1);
      }
      if (response.statusCode() == 404) throw error(Kind.NOT_FOUND, "MANIFEST_NOT_FOUND");
      if (response.statusCode() != 200 || body.length > MAX_BYTES)
        throw error(Kind.UPSTREAM_FAILED, "MANIFEST_UNAVAILABLE");
    } catch (java.io.IOException ex) {
      throw error(Kind.UPSTREAM_FAILED, "MANIFEST_UNAVAILABLE");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw error(Kind.UPSTREAM_FAILED, "MANIFEST_UNAVAILABLE");
    }
    return Optional.of(parse(body, app, platform));
  }

  ReleaseManifest parse(byte[] body, AppKind app, Platform platform) {
    final JsonNode j;
    try {
      j = JSON.readTree(body);
    } catch (RuntimeException ex) {
      throw invalid();
    }
    var declaredApp = AppKind.parse(text(j, "app")).orElseThrow(this::invalid);
    var declaredPlatform = Platform.parse(text(j, "platform")).orElseThrow(this::invalid);
    if (declaredApp != app || declaredPlatform != platform) throw invalid();
    String version = text(j, "versionName");
    String url = text(j, "url");
    String sha = text(j, "sha256");
    String notes = j.path("notes").isString() ? j.path("notes").asString().strip() : null;
    String commit = j.path("commit").isString() ? j.path("commit").asString().strip() : null;
    int build = j.path("buildNumber").isInt() ? j.path("buildNumber").asInt() : -1;
    long size = j.path("sizeBytes").canConvertToLong() ? j.path("sizeBytes").asLong() : -1;
    if (version == null
        || !version.matches("[0-9A-Za-z.+\\-]{1,40}")
        || build <= 0
        || size <= 0
        || sha == null
        || !sha.matches("[0-9a-f]{64}")
        || url == null
        || url.length() > 500
        || !underPrefix(url)
        || (notes != null && notes.length() > 2000)
        || (commit != null && !commit.matches("[0-9a-f]{7,64}"))) throw invalid();
    return new ReleaseManifest(
        app, platform, version, build, url, sha, size, notes == null || notes.isEmpty() ? null : notes, commit);
  }

  /** Compares parsed URIs: scheme, host and port must match; the path must lie under the prefix. */
  boolean underPrefix(String url) {
    final URI candidate;
    try {
      candidate = new URI(url).normalize();
    } catch (java.net.URISyntaxException ex) {
      return false;
    }
    var prefix = URI.create(downloadPrefix);
    String path = candidate.getRawPath();
    return candidate.getScheme() != null
        && candidate.getScheme().equalsIgnoreCase(prefix.getScheme())
        && candidate.getHost() != null
        && candidate.getHost().equalsIgnoreCase(prefix.getHost())
        && candidate.getPort() == prefix.getPort()
        && candidate.getUserInfo() == null
        && candidate.getQuery() == null
        && candidate.getFragment() == null
        && path != null
        && !path.contains("/../")
        && path.startsWith(prefix.getRawPath())
        && path.length() > prefix.getRawPath().length();
  }

  private static String text(JsonNode j, String field) {
    return j.path(field).isString() ? j.path(field).asString().strip() : null;
  }

  private DomainException invalid() {
    return error(Kind.UPSTREAM_FAILED, "MANIFEST_INVALID");
  }

  private static DomainException error(Kind status, String code) {
    return new DomainException(status, code);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String requireSafe(String value) {
    var uri = URI.create(value.strip());
    boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
    if (!("https".equals(uri.getScheme()) || ("http".equals(uri.getScheme()) && loopback))
        || uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null)
      throw new IllegalStateException("Release manifest URLs must be https (http only on loopback)");
    return value.strip();
  }
}
