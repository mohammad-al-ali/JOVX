package com.jovx.api.notifications.push;

import com.jovx.api.notifications.model.NotificationModels.PushResult;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Firebase Cloud Messaging HTTP v1 with a service account, without extra libraries: a signed JWT
 * (RS256) is exchanged for a one-hour OAuth access token, cached until shortly before it expires.
 * The service account JSON is read once from configuration and never logged.
 */
public final class FcmPushSender implements PushSender {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
  private final String projectId;
  private final String clientEmail;
  private final String tokenUri;
  private final PrivateKey key;
  private final Clock clock;
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private String accessToken;
  private Instant accessTokenExpires = Instant.EPOCH;

  private FcmPushSender(String projectId, String clientEmail, String tokenUri, PrivateKey key, Clock clock) {
    this.projectId = projectId; this.clientEmail = clientEmail; this.tokenUri = tokenUri; this.key = key;
    this.clock = clock;
  }

  /** @param base64Json the service account JSON file, base64-encoded (one line, safe in env vars). */
  public static FcmPushSender fromBase64(String base64Json, Clock clock) {
    try {
      JsonNode j = JSON.readTree(Base64.getDecoder().decode(base64Json.strip()));
      String pem = j.path("private_key").asString().replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
      var key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
      String tokenUri = j.path("token_uri").asString("https://oauth2.googleapis.com/token");
      String project = j.path("project_id").asString();
      String email = j.path("client_email").asString();
      if (project.isBlank() || email.isBlank()) throw new IllegalArgumentException("incomplete");
      return new FcmPushSender(project, email, tokenUri, key, clock);
    } catch (Exception e) {
      // Never echo the secret or the parser message, which may quote it.
      throw new IllegalStateException("FIREBASE_SERVICE_ACCOUNT_BASE64 is not a valid service account file");
    }
  }

  public boolean configured() {
    return true;
  }

  public synchronized PushResult send(String token, String title, String body, Map<String, String> data) {
    try {
      var message = new LinkedHashMap<String, Object>();
      message.put("token", token);
      message.put("notification", Map.of("title", title, "body", body));
      message.put("data", data);
      message.put("android", Map.of("priority", "HIGH"));
      var request = HttpRequest.newBuilder(URI.create("https://fcm.googleapis.com/v1/projects/" + projectId + "/messages:send"))
          .timeout(Duration.ofSeconds(15))
          .header("Authorization", "Bearer " + accessToken())
          .header("Content-Type", "application/json; charset=UTF-8")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("message", message))))
          .build();
      var response = http.send(request, HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status == 200) return PushResult.SENT;
      if (status == 404 || (status == 400 && response.body().contains("registration token"))) return PushResult.INVALID_TOKEN;
      if (status == 401) accessTokenExpires = Instant.EPOCH;
      if (status == 401 || status == 429 || status >= 500) return PushResult.RETRY;
      return PushResult.FAILED;
    } catch (java.io.IOException e) {
      return PushResult.RETRY;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return PushResult.RETRY;
    } catch (RuntimeException e) {
      return PushResult.RETRY;
    }
  }

  private String accessToken() throws java.io.IOException, InterruptedException {
    Instant now = clock.instant();
    if (accessToken != null && now.isBefore(accessTokenExpires)) return accessToken;
    long iat = now.getEpochSecond();
    String header = b64(JSON.writeValueAsBytes(Map.of("alg", "RS256", "typ", "JWT")));
    String claims = b64(JSON.writeValueAsBytes(Map.of("iss", clientEmail, "scope", SCOPE, "aud", tokenUri,
        "iat", iat, "exp", iat + 3600)));
    String unsigned = header + "." + claims;
    String assertion;
    try {
      var signer = Signature.getInstance("SHA256withRSA");
      signer.initSign(key);
      signer.update(unsigned.getBytes(StandardCharsets.US_ASCII));
      assertion = unsigned + "." + b64(signer.sign());
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("Cannot sign the Firebase token request");
    }
    String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
        + "&assertion=" + assertion;
    var response = http.send(HttpRequest.newBuilder(URI.create(tokenUri)).timeout(Duration.ofSeconds(15))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
    if (response.statusCode() != 200) throw new java.io.IOException("Firebase token request refused");
    JsonNode j = JSON.readTree(response.body());
    accessToken = j.path("access_token").asString();
    accessTokenExpires = now.plusSeconds(Math.max(60, j.path("expires_in").asLong(3600) - 120));
    return accessToken;
  }

  private static String b64(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
