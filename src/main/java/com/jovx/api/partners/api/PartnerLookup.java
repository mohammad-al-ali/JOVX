package com.jovx.api.partners.api;

import java.util.*;

/** Read-only partner facts for other modules (wallets, sales). Never exposes owner credentials. */
public interface PartnerLookup {
  /** The partner owned by a PARTNER account, whatever its status. */
  Optional<PartnerRef> ownedBy(UUID account);

  /** The partner that employs a PARTNER_EMPLOYEE account. */
  Optional<PartnerRef> employerOf(UUID employeeAccount);

  Optional<PartnerRef> byId(UUID partnerId);

  /** Every partner, for administration lists. */
  List<PartnerRef> all();

  /** status: PENDING, ACTIVE or SUSPENDED. name: the partner's establishment names joined. */
  record PartnerRef(UUID id, String status, String name) {
    public boolean active() {
      return "ACTIVE".equals(status);
    }
  }
}
