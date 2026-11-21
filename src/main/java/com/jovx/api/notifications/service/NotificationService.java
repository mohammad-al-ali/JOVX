package com.jovx.api.notifications.service;

import static com.jovx.api.notifications.model.NotificationModels.*;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.notifications.api.Notifier;
import com.jovx.api.notifications.dto.NotificationDtos.*;
import com.jovx.api.notifications.model.NotificationModels.*;
import com.jovx.api.notifications.repository.NotificationRepository;
import com.jovx.api.security.api.ActorAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Raises notifications (Notifier), the inbox, device tokens and SUPER_ADMIN's per-kind switches. */
@Service
@Profile("local-postgres")
public class NotificationService implements Notifier {
  /** Only these roles receive notifications: partner employees and administration do not (decision 31). */
  private static final Set<String> RECEIVERS = Set.of("MEMBER", "PARTNER");
  private final NotificationRepository repository;
  private final ActorAccess actors;
  private final TransactionTemplate tx;
  private final Clock clock;

  public NotificationService(NotificationRepository repository, ActorAccess actors,
      @Qualifier("notificationTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.actors = actors; this.tx = tx; this.clock = clock;
  }

  // ---- Notifier (caller's transaction) ----

  public void notify(UUID account, String kind, String title, String body, Map<String, String> data, Instant now) {
    if (!TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("Notifier must join the caller's transaction");
    var setting = repository.setting(kind).orElseThrow(() -> new IllegalArgumentException("Unknown notification kind"));
    if (!setting.enabled()) return;
    repository.insert(new Notification(UUID.randomUUID(), account, kind, Rules.clip(title, MAX_TITLE),
        Rules.clip(body, MAX_BODY), data == null ? Map.of() : Map.copyOf(data), now, null));
  }

  // ---- the signed-in member or partner ----

  public InboxView inbox(UUID actor, UUID sid, String role, int offset, int limit) {
    return tx.execute(s -> {
      requireReceiver(actor, sid, role);
      var items = repository.inbox(actor, offset, limit).stream().map(NotificationView::of).toList();
      return new InboxView(repository.unread(actor), items);
    });
  }

  public void markRead(UUID actor, UUID sid, String role, UUID id) {
    tx.executeWithoutResult(s -> {
      requireReceiver(actor, sid, role);
      if (!repository.markRead(actor, id, clock.instant())) throw new DomainException(Kind.NOT_FOUND, "NOTIFICATION_NOT_FOUND");
    });
  }

  public void markAllRead(UUID actor, UUID sid, String role) {
    tx.executeWithoutResult(s -> {
      requireReceiver(actor, sid, role);
      repository.markAllRead(actor, clock.instant());
    });
  }

  public void registerDevice(UUID actor, UUID sid, String role, DeviceRequest r) {
    tx.executeWithoutResult(s -> {
      requireReceiver(actor, sid, role);
      repository.saveToken(actor, r.token().strip(), r.platform(), clock.instant());
    });
  }

  /** At logout; another account's token is never touched. */
  public void forgetDevice(UUID actor, UUID sid, String role, String token) {
    tx.executeWithoutResult(s -> {
      requireReceiver(actor, sid, role);
      repository.deleteToken(actor, token.strip());
    });
  }

  // ---- SUPER_ADMIN ----

  public List<SettingView> settings(UUID actor, UUID sid) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "SUPER_ADMIN");
      return repository.settings().stream().map(SettingView::of).toList();
    });
  }

  public List<SettingView> updateSettings(UUID actor, UUID sid, List<SettingChange> changes) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "SUPER_ADMIN");
      Instant now = clock.instant();
      for (var c : changes)
        if (!repository.updateSetting(c.kind(), c.enabled(), actor, now))
          throw new DomainException(Kind.INVALID, "UNKNOWN_NOTIFICATION_KIND");
      return repository.settings().stream().map(SettingView::of).toList();
    });
  }

  private void requireReceiver(UUID actor, UUID sid, String role) {
    if (!RECEIVERS.contains(role)) throw new DomainException(Kind.FORBIDDEN, "FORBIDDEN");
    actors.requireRole(actor, sid, role);
  }
}
