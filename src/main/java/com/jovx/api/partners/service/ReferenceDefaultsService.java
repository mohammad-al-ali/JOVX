package com.jovx.api.partners.service;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.partners.model.PartnerModels.LocationDefaults;
import com.jovx.api.partners.repository.ReferenceDefaultsRepository;
import com.jovx.api.security.api.AdministrationAccess;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Default city and neighborhood (user decision 2026-11-19): preselected in partner registration,
 * establishment/branch forms and the public directory filter. Changed by SUPER_ADMIN or an ADMIN
 * holding partner.create, the same actors that maintain the city and neighborhood lists.
 */
@Service
@Profile("local-postgres")
public class ReferenceDefaultsService {
  static final String PERMISSION = "partner.create";
  private final ReferenceDefaultsRepository repository;
  private final TransactionTemplate tx;
  private final AdministrationAccess access;
  private final Clock clock;

  public ReferenceDefaultsService(
      ReferenceDefaultsRepository repository,
      @Qualifier("partnerTransactions") TransactionTemplate tx,
      AdministrationAccess access,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.access = access;
    this.clock = clock;
  }

  /** Empty until the defaults are set; callers then preselect nothing. */
  public Optional<LocationDefaults> current() {
    return repository.current();
  }

  public LocationDefaults change(UUID actor, UUID session, UUID city, UUID address) {
    return tx.execute(
        s -> {
          access.require(actor, session, PERMISSION);
          if (!repository.lockAddressOfCity(address, city))
            throw new DomainException(Kind.INVALID, "INVALID_LOCATION_OR_CATEGORY");
          repository.save(city, address, actor, clock.instant());
          return repository.current().orElseThrow();
        });
  }
}
