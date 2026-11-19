package com.jovx.api.partners.service;

import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.partners.repository.PartnerLookupRepository;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** partners::api implementation; the SQL lives in {@link PartnerLookupRepository}. */
@Service
@Profile("local-postgres")
public class PartnerLookupService implements PartnerLookup {
  private final PartnerLookupRepository repository;

  public PartnerLookupService(PartnerLookupRepository repository) {
    this.repository = repository;
  }

  public Optional<PartnerRef> ownedBy(UUID account) {
    return repository.ownedBy(account);
  }

  public Optional<PartnerRef> employerOf(UUID employee) {
    return repository.employerOf(employee);
  }

  public Optional<PartnerRef> byId(UUID partner) {
    return repository.byId(partner);
  }

  public List<PartnerRef> all() {
    return repository.all();
  }

  public Optional<StoreRef> store(UUID store) {
    return repository.store(store);
  }

  public List<StoreRef> stores(UUID partner) {
    return repository.stores(partner);
  }

  public Set<UUID> employeeStores(UUID employee) {
    return repository.employeeStores(employee);
  }
}
