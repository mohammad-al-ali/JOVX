package com.jovx.api.notifications.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class NotificationModels {
  private NotificationModels() {}

  public static final int MAX_TITLE = 120, MAX_BODY = 500, MAX_ATTEMPTS = 6;

  public enum Platform { ANDROID, IOS, WEB }

  public enum OutboxStatus { PENDING, SENT, SKIPPED, FAILED }

  public enum Audience { MEMBER, PARTNER }

  public record Notification(
      UUID id, UUID accountId, String kind, String title, String body, Map<String, String> data, Instant createdAt,
      Instant readAt) {}

  /** A due outbox row joined with what the push needs. */
  public record Pending(UUID notificationId, UUID accountId, String title, String body, Map<String, String> data,
      int attempts) {}

  public record EventSetting(String kind, Audience audience, boolean enabled) {}

  /** Outcome of one push to one device. */
  public enum PushResult { SENT, INVALID_TOKEN, RETRY, FAILED }

  /** Pure delivery rules. */
  public static final class Rules {
    private Rules() {}

    /** 30 s, 1 min, 2 min, 4 min, 8 min between attempts; FAILED after {@link #MAX_ATTEMPTS}. */
    public static Duration backoff(int attemptsSoFar) {
      return Duration.ofSeconds(30L << Math.min(Math.max(attemptsSoFar - 1, 0), 10));
    }

    /** Titles and bodies are cut to the column sizes; blank text is never stored. */
    public static String clip(String text, int max) {
      String t = text == null ? "" : text.strip();
      return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }
  }
}
