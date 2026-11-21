package com.jovx.api.notifications.dto;

import com.jovx.api.notifications.model.NotificationModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class NotificationDtos {
  private NotificationDtos() {}

  public record DeviceRequest(@NotBlank @Size(max = 512) String token, @NotNull Platform platform) {
    @Override
    public String toString() {
      return "DeviceRequest[redacted]";
    }
  }

  public record DeviceRemoval(@NotBlank @Size(max = 512) String token) {
    @Override
    public String toString() {
      return "DeviceRemoval[redacted]";
    }
  }

  public record NotificationView(
      UUID id, String kind, String title, String body, Map<String, String> data, Instant createdAt, boolean read) {
    public static NotificationView of(Notification n) {
      return new NotificationView(n.id(), n.kind(), n.title(), n.body(), n.data(), n.createdAt(), n.readAt() != null);
    }
  }

  public record InboxView(long unread, List<NotificationView> items) {}

  public record SettingView(String kind, Audience audience, boolean enabled) {
    public static SettingView of(EventSetting s) {
      return new SettingView(s.kind(), s.audience(), s.enabled());
    }
  }

  public record SettingChange(@NotBlank @Size(max = 40) String kind, @NotNull Boolean enabled) {}

  public record SettingsRequest(@NotEmpty @Size(max = 50) List<@Valid SettingChange> changes) {}

  /** partnerId null: every active partner. */
  public record MessageRequest(
      @NotNull UUID requestId, UUID partnerId, @NotBlank @Size(max = 120) String title,
      @NotBlank @Size(max = 500) String body) {}

  public record MessageResult(int recipients) {}
}
