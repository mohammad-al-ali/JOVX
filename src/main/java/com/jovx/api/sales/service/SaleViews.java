package com.jovx.api.sales.service;

import com.jovx.api.membership.api.MemberScans;
import com.jovx.api.membership.api.MemberScans.ScannedMember;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.sales.dto.SalesDtos.*;
import com.jovx.api.sales.model.SalesModels.*;
import com.jovx.api.security.api.AccountDirectory;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Turns stored sales into receipts, adding store, partner, member and seller names. */
@Component
@Profile("local-postgres")
class SaleViews {
  private final AccountDirectory accounts;
  private final PartnerLookup partners;
  private final MemberScans members;

  SaleViews(AccountDirectory accounts, PartnerLookup partners, MemberScans members) {
    this.accounts = accounts; this.partners = partners; this.members = members;
  }

  MemberView member(ScannedMember m) {
    var who = accounts.summaries(List.of(m.memberAccount())).get(m.memberAccount());
    return new MemberView(who == null ? "" : who.name(), m.memberNumber(), m.maskedCard());
  }

  SaleView sale(Sale sale, List<SaleLine> lines) {
    return sales(List.of(sale), Map.of(sale.id(), lines)).getFirst();
  }

  List<SaleView> sales(List<Sale> sales, Map<UUID, List<SaleLine>> lines) {
    if (sales.isEmpty()) return List.of();
    var people = new HashSet<UUID>();
    sales.forEach(s -> { people.add(s.memberAccount()); people.add(s.sellerId()); });
    var names = accounts.summaries(people);
    var storeNames = new HashMap<UUID, String>();
    var partnerNames = new HashMap<UUID, String>();
    var cards = new HashMap<UUID, Optional<ScannedMember>>();
    var result = new ArrayList<SaleView>();
    for (var s : sales) {
      String store = storeNames.computeIfAbsent(s.storeId(),
          id -> partners.store(id).map(PartnerLookup.StoreRef::name).orElse(""));
      String partner = partnerNames.computeIfAbsent(s.partnerId(),
          id -> partners.byId(id).map(PartnerLookup.PartnerRef::name).orElse(""));
      var card = cards.computeIfAbsent(s.cardId(), members::byCardId);
      var member = names.get(s.memberAccount());
      var seller = names.get(s.sellerId());
      result.add(new SaleView(s.id(), s.createdAt(), s.storeId(), store, partner, s.kind(), s.currency(),
          s.grossAmount(), s.discountAmount(), s.netAmount(), s.discountPercent(), s.points(),
          new MemberView(member == null ? "" : member.name(), card.map(ScannedMember::memberNumber).orElse(""),
              card.map(ScannedMember::maskedCard).orElse("")),
          seller == null ? "" : seller.name(),
          lines.getOrDefault(s.id(), List.of()).stream().map(LineView::of).toList()));
    }
    return result;
  }
}
