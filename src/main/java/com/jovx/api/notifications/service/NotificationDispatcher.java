package com.jovx.api.notifications.service;

import static com.jovx.api.notifications.model.NotificationModels.*;

import com.jovx.api.notifications.model.NotificationModels.*;
import com.jovx.api.notifications.push.PushSender;
import com.jovx.api.notifications.repository.NotificationRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends committed notifications. Rows are locked with SKIP LOCKED, so several instances never send
 * one notification twice; transient failures retry with backoff up to MAX_ATTEMPTS.
 */
@Service
@Profile("local-postgres")
public class NotificationDispatcher {
  private static final int BATCH = 20;
  private final NotificationRepository repository;
  private final PushSender sender;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final boolean enabled;

  public NotificationDispatcher(NotificationRepository repository, PushSender sender,
      @Qualifier("notificationTransactions") TransactionTemplate tx, Clock clock,
      @Value("${jovx.notifications.dispatch-enabled:true}") boolean enabled) {
    this.repository = repository; this.sender = sender; this.tx = tx; this.clock = clock; this.enabled = enabled;
  }

  @Scheduled(fixedDelayString = "${jovx.notifications.dispatch-interval:10s}", initialDelayString = "20s")
  void scheduled() {
    if (enabled) dispatchDue();
  }

  /** One batch; returns how many notifications were handled. */
  public int dispatchDue() {
    Integer handled = tx.execute(s -> {
      Instant now = clock.instant();
      var due = repository.lockDue(now, BATCH);
      for (var n : due) deliver(n, now);
      return due.size();
    });
    return handled == null ? 0 : handled;
  }

  private void deliver(Pending n, Instant now) {
    int attempts = n.attempts() + 1;
    if (!sender.configured()) {
      repository.finish(n.notificationId(), OutboxStatus.SKIPPED, attempts, "PUSH_NOT_CONFIGURED", now);
      return;
    }
    var tokens = repository.tokens(n.accountId());
    if (tokens.isEmpty()) {
      repository.finish(n.notificationId(), OutboxStatus.SKIPPED, attempts, "NO_DEVICE", now);
      return;
    }
    boolean sent = false, retry = false;
    for (String token : tokens) {
      switch (sender.send(token, n.title(), n.body(), n.data())) {
        case SENT -> sent = true;
        case INVALID_TOKEN -> repository.forgetToken(token);
        case RETRY -> retry = true;
        case FAILED -> { }
      }
    }
    if (sent) repository.finish(n.notificationId(), OutboxStatus.SENT, attempts, null, now);
    else if (retry && attempts < MAX_ATTEMPTS)
      repository.retryLater(n.notificationId(), attempts, now.plus(Rules.backoff(attempts)), "RETRY", now);
    else repository.finish(n.notificationId(), OutboxStatus.FAILED, attempts, retry ? "RETRIES_EXHAUSTED" : "REFUSED", now);
  }
}
