package com.jovx.api.partners.service;

import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.model.PartnerModels.*;
import com.jovx.api.partners.repository.PartnerRepository;
import com.jovx.api.security.api.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Establishments, branches and discount after registration. Approved 2026-11-17:
 * establishments are added/edited by administration (partner.create) and by the owning Partner;
 * branches by administration (partner.branch.manage) and the owning Partner; any branch may be
 * suspended; the partner-wide minimum discount is administration-only (partner.create).
 */
@Service
@Profile("local-postgres")
public class PartnerManagementService {
  static final String CREATE = "partner.create", BRANCHES = "partner.branch.manage";
  private final PartnerRepository repository;
  private final TransactionTemplate tx;
  private final AdministrationAccess admin;
  private final ActorAccess actors;
  private final AccountDirectory directory;
  private final Clock clock;

  public PartnerManagementService(
      PartnerRepository repository,
      @Qualifier("partnerTransactions") TransactionTemplate tx,
      AdministrationAccess admin,
      ActorAccess actors,
      AccountDirectory directory,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.admin = admin;
    this.actors = actors;
    this.directory = directory;
    this.clock = clock;
  }

  // ---- administration ----

  /**
   * One page (default 20) of partners; the search matches the owner's name or login phone, or an
   * establishment name. Three queries per page regardless of size (no per-partner queries).
   */
  public List<PartnerSummary> list(UUID actor, UUID sid, String query, int offset, int limit) {
    return tx.execute(
        s -> {
          admin.requireAny(actor, sid, Set.of(CREATE, BRANCHES));
          String search = query == null || query.isBlank() ? null : query.strip();
          List<UUID> owners =
              search == null ? List.of() : directory.search("PARTNER", search, 100);
          var page = repository.ownerPage(search, owners, offset, limit);
          var accounts = directory.summaries(page.stream().map(OwnerListing::accountId).toList());
          return page.stream()
              .map(
                  o -> {
                    var account = accounts.get(o.accountId());
                    return new PartnerSummary(
                        o.id(),
                        o.accountId(),
                        account == null ? null : account.name(),
                        account == null ? null : account.phone(),
                        o.status(),
                        o.minimum(),
                        o.establishmentNames(),
                        o.branchCount());
                  })
              .toList();
        });
  }

  public PartnerDetail detail(UUID actor, UUID sid, UUID partner) {
    return tx.execute(
        s -> {
          admin.requireAny(actor, sid, Set.of(CREATE, BRANCHES));
          return detail(owner(partner, false));
        });
  }

  public void discount(UUID actor, UUID sid, UUID partner, BigDecimal value) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, CREATE);
          var owner = owner(partner, true);
          new PartnerDiscount(value);
          if (value.stripTrailingZeros().scale() > 2)
            throw error(Kind.INVALID, "INVALID_DISCOUNT_PRECISION");
          repository.updateDiscount(owner.id(), value);
          repository.audit(owner.id(), actor, "DISCOUNT_CHANGED", clock.instant());
        });
  }

  public EstablishmentCreatedResponse adminAddEstablishment(
      UUID actor, UUID sid, UUID partner, NewEstablishmentRequest request) {
    return tx.execute(
        s -> {
          admin.require(actor, sid, CREATE);
          return addEstablishment(actor, owner(partner, true), request);
        });
  }

  public void adminEditEstablishment(
      UUID actor, UUID sid, UUID partner, UUID establishment, EstablishmentRequest request) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, CREATE);
          editEstablishment(actor, owner(partner, true), establishment, request);
        });
  }

  public BranchCreatedResponse adminAddBranch(
      UUID actor, UUID sid, UUID partner, UUID establishment, BranchRequest request) {
    return tx.execute(
        s -> {
          admin.require(actor, sid, BRANCHES);
          return addBranch(actor, owner(partner, true), establishment, request);
        });
  }

  public void adminEditBranch(
      UUID actor, UUID sid, UUID partner, UUID branch, BranchRequest request) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, BRANCHES);
          editBranch(actor, owner(partner, true), branch, request);
        });
  }

  public void adminBranchStatus(UUID actor, UUID sid, UUID partner, UUID branch, String status) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, BRANCHES);
          branchStatus(actor, owner(partner, true), branch, status);
        });
  }

  // ---- owning partner ----

  public PartnerDetail profile(UUID actor, UUID sid) {
    return tx.execute(s -> detail(self(actor, sid)));
  }

  public EstablishmentCreatedResponse ownAddEstablishment(
      UUID actor, UUID sid, NewEstablishmentRequest request) {
    return tx.execute(s -> addEstablishment(actor, self(actor, sid), request));
  }

  public void ownEditEstablishment(
      UUID actor, UUID sid, UUID establishment, EstablishmentRequest request) {
    tx.executeWithoutResult(s -> editEstablishment(actor, self(actor, sid), establishment, request));
  }

  public BranchCreatedResponse ownAddBranch(
      UUID actor, UUID sid, UUID establishment, BranchRequest request) {
    return tx.execute(s -> addBranch(actor, self(actor, sid), establishment, request));
  }

  public void ownEditBranch(UUID actor, UUID sid, UUID branch, BranchRequest request) {
    tx.executeWithoutResult(s -> editBranch(actor, self(actor, sid), branch, request));
  }

  public void ownBranchStatus(UUID actor, UUID sid, UUID branch, String status) {
    tx.executeWithoutResult(s -> branchStatus(actor, self(actor, sid), branch, status));
  }

  // ---- shared rules ----

  private PartnerDetail detail(Owner owner) {
    var account = directory.summaries(List.of(owner.accountId())).get(owner.accountId());
    return new PartnerDetail(
        owner.id(),
        owner.accountId(),
        account == null ? null : account.name(),
        account == null ? null : account.phone(),
        owner.status(),
        owner.minimum(),
        repository.establishmentDetails(owner.id()));
  }

  private EstablishmentCreatedResponse addEstablishment(
      UUID actor, Owner owner, NewEstablishmentRequest request) {
    requireCategory(request.categoryId());
    requireLocation(request.cityId(), request.addressId());
    // A pending partner's extra establishments are activated together with the partner.
    String status = "ACTIVE".equals(owner.status()) ? "ACTIVE" : "PENDING";
    UUID establishment = UUID.randomUUID(), branch = UUID.randomUUID();
    repository.insertEstablishment(
        establishment,
        owner.id(),
        request.categoryId(),
        request.name().strip(),
        optional(request.contactPhone()),
        status);
    repository.insertBranch(
        branch,
        establishment,
        request.branchName().strip(),
        request.cityId(),
        request.addressId(),
        optional(request.addressDetails()));
    repository.audit(owner.id(), actor, "ESTABLISHMENT_CREATED", clock.instant());
    return new EstablishmentCreatedResponse(establishment, branch, status);
  }

  private void editEstablishment(
      UUID actor, Owner owner, UUID establishment, EstablishmentRequest request) {
    if (repository.lockEstablishment(establishment, owner.id()).isEmpty())
      throw error(Kind.NOT_FOUND, "ESTABLISHMENT_NOT_FOUND");
    requireCategory(request.categoryId());
    // Contact phone is independent of the login phone; this never touches credentials.
    repository.updateEstablishment(
        establishment,
        request.name().strip(),
        request.categoryId(),
        optional(request.contactPhone()));
    repository.audit(owner.id(), actor, "ESTABLISHMENT_UPDATED", clock.instant());
  }

  private BranchCreatedResponse addBranch(
      UUID actor, Owner owner, UUID establishment, BranchRequest request) {
    if (repository.lockEstablishment(establishment, owner.id()).isEmpty())
      throw error(Kind.NOT_FOUND, "ESTABLISHMENT_NOT_FOUND");
    requireLocation(request.cityId(), request.addressId());
    UUID branch = UUID.randomUUID();
    repository.insertBranch(
        branch,
        establishment,
        request.name().strip(),
        request.cityId(),
        request.addressId(),
        optional(request.addressDetails()));
    repository.audit(owner.id(), actor, "BRANCH_CREATED", clock.instant());
    return new BranchCreatedResponse(branch, "ACTIVE");
  }

  private void editBranch(UUID actor, Owner owner, UUID branch, BranchRequest request) {
    if (repository.lockBranch(branch, owner.id()).isEmpty())
      throw error(Kind.NOT_FOUND, "BRANCH_NOT_FOUND");
    requireLocation(request.cityId(), request.addressId());
    repository.updateBranch(
        branch,
        request.name().strip(),
        request.cityId(),
        request.addressId(),
        optional(request.addressDetails()));
    repository.audit(owner.id(), actor, "BRANCH_UPDATED", clock.instant());
  }

  private void branchStatus(UUID actor, Owner owner, UUID branch, String status) {
    var current =
        repository
            .lockBranch(branch, owner.id())
            .orElseThrow(() -> error(Kind.NOT_FOUND, "BRANCH_NOT_FOUND"));
    if (current.equals(status)) return;
    // Employees assigned only to suspended branches lose login eligibility on their next request.
    repository.setBranchStatus(branch, status);
    repository.audit(
        owner.id(),
        actor,
        "ACTIVE".equals(status) ? "BRANCH_REACTIVATED" : "BRANCH_SUSPENDED",
        clock.instant());
  }

  private Owner owner(UUID partner, boolean lock) {
    var owner = lock ? repository.lockOwner(partner) : repository.findOwner(partner);
    return owner.orElseThrow(() -> error(Kind.NOT_FOUND, "PARTNER_NOT_FOUND"));
  }

  private Owner self(UUID actor, UUID sid) {
    actors.requireRole(actor, sid, "PARTNER");
    return repository
        .activeOwner(actor, true)
        .orElseThrow(() -> error(Kind.FORBIDDEN, "FORBIDDEN"));
  }

  private void requireCategory(UUID category) {
    if (!repository.categoryExists(category))
      throw error(Kind.INVALID, "INVALID_LOCATION_OR_CATEGORY");
  }

  private void requireLocation(UUID city, UUID address) {
    if (!repository.addressBelongsToCity(address, city))
      throw error(Kind.INVALID, "INVALID_LOCATION_OR_CATEGORY");
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static DomainException error(Kind status, String code) {
    return new DomainException(status, code);
  }
}
