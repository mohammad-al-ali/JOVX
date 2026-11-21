package com.jovx.api.sales.service;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.partners.api.PartnerLookup.PartnerRef;
import com.jovx.api.partners.api.PartnerLookup.StoreRef;
import com.jovx.api.security.api.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Who is selling: the owning partner (every store, every action) or a partner employee
 * (assigned stores only, and only the grants the partner gave). Checked inside each transaction.
 */
@Component
@Profile("local-postgres")
class Sellers {
  static final String SCAN = "member.scan", SELL = "sale.confirm", FREE = "sale.free",
      READ_OWN = "sale.read.own", READ_STORES = "sale.read.branch", REPORT_STORES = "report.read.branch",
      REPORT_PARTNER = "report.read.partner";
  private final PartnerLookup partners;
  private final ActorAccess actors;
  private final EmployeeAccounts employees;

  Sellers(PartnerLookup partners, ActorAccess actors, EmployeeAccounts employees) {
    this.partners = partners; this.actors = actors; this.employees = employees;
  }

  record Seller(UUID account, PartnerRef partner, boolean owner, Map<UUID, StoreRef> stores, Set<String> grants) {
    boolean may(String grant) {
      return owner || grants.contains(grant);
    }

    void require(String grant) {
      if (!may(grant)) throw new DomainException(Kind.FORBIDDEN, "FORBIDDEN");
    }

    /** A store this seller works in, active, of an active partner: the only place a sale may happen. */
    StoreRef sellingStore(UUID storeId) {
      if (!partner.active()) throw new DomainException(Kind.CONFLICT, "PARTNER_NOT_ACTIVE");
      var store = stores.get(storeId);
      if (store == null) throw new DomainException(Kind.NOT_FOUND, "STORE_NOT_FOUND");
      if (!store.active()) throw new DomainException(Kind.CONFLICT, "STORE_NOT_ACTIVE");
      return store;
    }
  }

  Seller of(UUID actor, UUID sid, String role) {
    if ("PARTNER".equals(role)) {
      actors.requireRole(actor, sid, "PARTNER");
      var partner = partners.ownedBy(actor).orElseThrow(() -> new DomainException(Kind.FORBIDDEN, "FORBIDDEN"));
      return new Seller(actor, partner, true, byId(partners.stores(partner.id())), Set.of());
    }
    actors.requireRole(actor, sid, "PARTNER_EMPLOYEE");
    var partner = partners.employerOf(actor).orElseThrow(() -> new DomainException(Kind.FORBIDDEN, "FORBIDDEN"));
    var assigned = partners.employeeStores(actor);
    var stores = byId(partners.stores(partner.id()).stream().filter(s -> assigned.contains(s.id())).toList());
    return new Seller(actor, partner, false, stores, Set.copyOf(employees.effectivePermissions(actor)));
  }

  private static Map<UUID, StoreRef> byId(List<StoreRef> stores) {
    var map = new LinkedHashMap<UUID, StoreRef>();
    stores.forEach(s -> map.put(s.id(), s));
    return map;
  }
}
