package com.jovx.api.notifications.controller;

import com.jovx.api.notifications.dto.NotificationDtos.*;
import com.jovx.api.notifications.service.AdminMessageService;
import com.jovx.api.notifications.service.NotificationService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
public class NotificationController {
  private static final String RECEIVER = "hasRole('MEMBER') or hasRole('PARTNER')";
  private final NotificationService notifications;
  private final AdminMessageService messages;

  public NotificationController(NotificationService notifications, AdminMessageService messages) {
    this.notifications = notifications; this.messages = messages;
  }

  @PutMapping("/api/v1/devices/token")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(RECEIVER)
  public void registerDevice(JwtAuthenticationToken a, @Valid @RequestBody DeviceRequest body) {
    notifications.registerDevice(id(a), sid(a), role(a), body);
  }

  @PostMapping("/api/v1/devices/token/removal")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(RECEIVER)
  public void forgetDevice(JwtAuthenticationToken a, @Valid @RequestBody DeviceRemoval body) {
    notifications.forgetDevice(id(a), sid(a), role(a), body.token());
  }

  @GetMapping("/api/v1/notifications")
  @PreAuthorize(RECEIVER)
  public InboxView inbox(
      JwtAuthenticationToken a, @RequestParam(defaultValue = "0") int offset, @RequestParam(defaultValue = "30") int limit) {
    return notifications.inbox(id(a), sid(a), role(a), offset, limit);
  }

  @PostMapping("/api/v1/notifications/{notificationId}/read")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(RECEIVER)
  public void read(JwtAuthenticationToken a, @PathVariable UUID notificationId) {
    notifications.markRead(id(a), sid(a), role(a), notificationId);
  }

  @PostMapping("/api/v1/notifications/read-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize(RECEIVER)
  public void readAll(JwtAuthenticationToken a) {
    notifications.markAllRead(id(a), sid(a), role(a));
  }

  @GetMapping("/api/v1/admin/notification-settings")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public List<SettingView> settings(JwtAuthenticationToken a) {
    return notifications.settings(id(a), sid(a));
  }

  @PutMapping("/api/v1/admin/notification-settings")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public List<SettingView> updateSettings(JwtAuthenticationToken a, @Valid @RequestBody SettingsRequest body) {
    return notifications.updateSettings(id(a), sid(a), body.changes());
  }

  @PostMapping("/api/v1/admin/partner-messages")
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasRole('SUPER_ADMIN') or hasAuthority('notification.manage')")
  public MessageResult message(JwtAuthenticationToken a, @Valid @RequestBody MessageRequest body) {
    return messages.send(id(a), sid(a), body);
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
