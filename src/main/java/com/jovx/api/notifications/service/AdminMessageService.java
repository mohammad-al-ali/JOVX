package com.jovx.api.notifications.service;

import static com.jovx.api.notifications.model.NotificationModels.*;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.notifications.api.Notifier;
import com.jovx.api.notifications.dto.NotificationDtos.*;
import com.jovx.api.notifications.model.NotificationModels.*;
import com.jovx.api.notifications.repository.NotificationRepository;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.security.api.AdministrationAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Administration messages to partners (decision 30): SUPER_ADMIN or ADMIN with notification.manage,
 * to one partner or every active partner. A retried requestId sends nothing twice.
 */
@Service
@Profile("local-postgres")
public class AdminMessageService {
  static final String GRANT = "notification.manage";
  private final NotificationRepository repository;
  private final Notifier notifier;
  private final PartnerLookup partners;
  private final AdministrationAccess admin;
  private final TransactionTemplate tx;
  private final Clock clock;

  public AdminMessageService(NotificationRepository repository, Notifier notifier, PartnerLookup partners,
      AdministrationAccess admin, @Qualifier("notificationTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.notifier = notifier; this.partners = partners; this.admin = admin;
    this.tx = tx; this.clock = clock;
  }

  public MessageResult send(UUID actor, UUID sid, MessageRequest r) {
    return tx.execute(s -> {
      admin.require(actor, sid, GRANT);
      repository.lockRequest(r.requestId());
      var earlier = repository.messageRecipients(r.requestId());
      if (earlier.isPresent()) return new MessageResult(earlier.get());
      String title = Rules.clip(r.title(), MAX_TITLE), body = Rules.clip(r.body(), MAX_BODY);
      if (title.isEmpty() || body.isEmpty()) throw new DomainException(Kind.INVALID, "MESSAGE_REQUIRED");
      List<PartnerLookup.PartnerRef> targets;
      if (r.partnerId() != null) {
        var one = partners.byId(r.partnerId()).orElseThrow(() -> new DomainException(Kind.NOT_FOUND, "PARTNER_NOT_FOUND"));
        targets = List.of(one);
      } else {
        targets = partners.all().stream().filter(PartnerLookup.PartnerRef::active).toList();
      }
      Instant now = clock.instant();
      UUID id = UUID.randomUUID();
      int recipients = 0;
      for (var p : targets) {
        var account = partners.ownerAccount(p.id());
        if (account.isEmpty()) continue;
        notifier.notify(account.get(), Notifier.ADMIN_MESSAGE, title, body, Map.of("messageId", id.toString()), now);
        recipients++;
      }
      repository.insertMessage(id, r.requestId(), r.partnerId(), title, body, recipients, actor, now);
      return new MessageResult(recipients);
    });
  }
}
