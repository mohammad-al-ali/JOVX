package com.jovx.api.partners.service;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.partners.dto.PartnerDtos.WorkspaceResponse;
import com.jovx.api.partners.repository.PartnerRepository;
import com.jovx.api.security.api.ActorAccess;
import com.jovx.api.security.api.EmployeeAccounts;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What the signed-in owner or employee may see in the partner app: the active stores in reach and,
 * for an employee, the explicitly granted permission keys (the owner holds every partner permission).
 */
@Service
@Profile("local-postgres")
public class PartnerWorkspaceService {
  private final PartnerRepository repository;
  private final PartnerLookupService lookup;
  private final EmployeeAccounts employees;
  private final ActorAccess actors;
  private final TransactionTemplate tx;

  public PartnerWorkspaceService(
      PartnerRepository repository,
      PartnerLookupService lookup,
      EmployeeAccounts employees,
      ActorAccess actors,
      @Qualifier("partnerTransactions") TransactionTemplate tx) {
    this.repository = repository;
    this.lookup = lookup;
    this.employees = employees;
    this.actors = actors;
    this.tx = tx;
  }

  public WorkspaceResponse workspace(UUID actor, UUID sid, boolean owner) {
    return tx.execute(
        s -> {
          actors.requireRole(actor, sid, owner ? "PARTNER" : "PARTNER_EMPLOYEE");
          var partner =
              (owner ? lookup.ownedBy(actor) : lookup.employerOf(actor))
                  .filter(p -> p.active())
                  .orElseThrow(() -> new DomainException(Kind.FORBIDDEN, "PARTNER_NOT_ACTIVE"));
          var stores = repository.activeStores(partner.id());
          if (owner)
            return new WorkspaceResponse(
                partner.id(), true, partner.minimumDiscountPercent(), stores, Set.of());
          var assigned = lookup.employeeStores(actor);
          return new WorkspaceResponse(
              partner.id(),
              false,
              partner.minimumDiscountPercent(),
              stores.stream().filter(st -> assigned.contains(st.id())).toList(),
              new TreeSet<>(employees.effectivePermissions(actor)));
        });
  }
}
