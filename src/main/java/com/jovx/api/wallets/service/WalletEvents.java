package com.jovx.api.wallets.service;

import com.jovx.api.notifications.api.Notifier;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.wallets.model.WalletModels.*;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Wallet notifications to the owning partner (decisions 23, 30, 31), inside the caller's transaction. */
@Component
@Profile("local-postgres")
class WalletEvents {
  private final Notifier notifier;
  private final PartnerLookup partners;

  WalletEvents(Notifier notifier, PartnerLookup partners) {
    this.notifier = notifier; this.partners = partners;
  }

  void decided(WalletRequest q, boolean approved, String reason, Instant now) {
    String what = q.kind() == RequestKind.TOPUP ? "طلب الشحن" : "طلب الإرجاع";
    String body = approved
        ? (q.kind() == RequestKind.TOPUP
            ? "أُضيفت " + number(q.amount()) + " نقطة إلى محفظتك."
            : "اعتُمد إرجاع " + number(q.amount()) + " نقطة من محفظتك.")
        : (q.kind() == RequestKind.TOPUP ? "لم يُشحن شيء." : "عادت النقاط المحجوزة إلى رصيدك.")
            + (reason == null ? "" : " السبب: " + reason);
    notifyOwner(q.partnerId(), Notifier.WALLET_REQUEST_RESULT, what + (approved ? " مقبول" : " مرفوض"), body,
        Map.of("requestId", q.id().toString()), now);
  }

  void lowBalance(UUID partnerId, long balance, long threshold, Instant now) {
    notifyOwner(partnerId, Notifier.LOW_BALANCE, "رصيد المحفظة منخفض",
        "رصيدك " + number(balance) + " نقطة، أقل من " + number(threshold) + ". اشحن المحفظة لتستمر في البيع.",
        Map.of(), now);
  }

  private void notifyOwner(UUID partnerId, String kind, String title, String body, Map<String, String> data, Instant now) {
    partners.ownerAccount(partnerId).ifPresent(owner -> notifier.notify(owner, kind, title, body, data, now));
  }

  private static String number(long value) {
    return String.format(Locale.ROOT, "%,d", value);
  }
}
