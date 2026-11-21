package com.jovx.api.sales.service;

import com.jovx.api.notifications.api.Notifier;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.sales.model.SalesModels.*;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Notifications raised by sales inside the sale's own transaction (decisions 15, 30, 31). */
@Component
@Profile("local-postgres")
class SaleEvents {
  private final Notifier notifier;
  private final PartnerLookup partners;

  SaleEvents(Notifier notifier, PartnerLookup partners) {
    this.notifier = notifier; this.partners = partners;
  }

  /** Decision 15: the member gets the receipt as a push. */
  void saleCompleted(Sale sale) {
    notifier.notify(sale.memberAccount(), Notifier.SALE_RECEIPT, "شراء من " + store(sale),
        "دفعت " + money(sale.netAmount()) + " ووفّرت " + money(sale.discountAmount()) + "، وأُضيفت "
            + number(sale.points()) + " نقطة إلى رصيدك.",
        Map.of("saleId", sale.id().toString()), sale.createdAt());
  }

  void cancellationRequested(Sale sale, Cancellation request) {
    notifier.notify(sale.memberAccount(), Notifier.SALE_CANCEL_REQUESTED, store(sale) + " يطلب إلغاء فاتورة",
        "فاتورة " + money(sale.netAmount()) + ". عند موافقتك تُسحب " + number(sale.points())
            + " نقطة من رصيدك. أمامك 24 ساعة للرد.",
        Map.of("saleId", sale.id().toString(), "cancellationId", request.id().toString()), request.requestedAt());
  }

  /** Decision 31: the owning partner, not the employee who sold. */
  void cancellationDecided(Sale sale, Cancellation request, boolean accepted, java.time.Instant now) {
    partners.ownerAccount(sale.partnerId()).ifPresent(owner -> notifier.notify(owner, Notifier.SALE_CANCEL_RESULT,
        accepted ? "أُلغيت فاتورة" : "رفض العضو إلغاء فاتورة",
        accepted
            ? "وافق العضو على إلغاء فاتورة " + money(sale.netAmount()) + " وعادت " + number(sale.points()) + " نقطة إلى محفظتك."
            : "بقيت فاتورة " + money(sale.netAmount()) + " كما هي.",
        Map.of("saleId", sale.id().toString(), "cancellationId", request.id().toString()), now));
  }

  private String store(Sale sale) {
    return partners.store(sale.storeId()).map(PartnerLookup.StoreRef::name).orElse("JOVX");
  }

  static String money(long amount) {
    return number(amount) + " ل.س";
  }

  static String number(long value) {
    return String.format(java.util.Locale.ROOT, "%,d", value);
  }
}
