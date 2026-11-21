package com.jovx.api.partners.api;

import java.math.BigDecimal;
import java.util.*;

/** Read-only partner facts for other modules (wallets, catalog, sales). Never exposes owner credentials. */
public interface PartnerLookup {
  /** The partner owned by a PARTNER account, whatever its status. */
  Optional<PartnerRef> ownedBy(UUID account);

  /** The partner that employs a PARTNER_EMPLOYEE account. */
  Optional<PartnerRef> employerOf(UUID employeeAccount);

  Optional<PartnerRef> byId(UUID partnerId);

  /** The PARTNER account that owns the partner (notifications go to the owner only, decision 31). */
  Optional<UUID> ownerAccount(UUID partnerId);

  /** Every partner, for administration lists. */
  List<PartnerRef> all();

  /** One store, whatever its status. */
  Optional<StoreRef> store(UUID storeId);

  /** Every store of a partner. */
  List<StoreRef> stores(UUID partnerId);

  /** Stores members and guests may browse: active stores of active partners, with the partner minimum. */
  List<ListedStore> listedStores();

  /** Stores a PARTNER_EMPLOYEE is assigned to. */
  Set<UUID> employeeStores(UUID employeeAccount);

  /** status: PENDING, ACTIVE or SUSPENDED. name: the partner's store names joined. */
  record PartnerRef(UUID id, String status, String name, BigDecimal minimumDiscountPercent) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  /** status: PENDING, ACTIVE or SUSPENDED. */
  record StoreRef(UUID id, UUID partnerId, String name, String status) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }

  record ListedStore(
      UUID id, UUID partnerId, String name, UUID cityId, String cityName, BigDecimal minimumDiscountPercent) {}
}
