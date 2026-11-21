package com.jovx.api.partners.service;

import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.model.PartnerModels.*;
import com.jovx.api.partners.repository.PartnerRepository;
import com.jovx.api.security.api.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores and discount after registration (decision 2026-11-19, docs/partner-stores-plan-ar.md):
 * stores are added, edited, suspended and given hours by administration (partner.store.manage)
 * and by the owning Partner; any store may be suspended; the partner-wide minimum discount is
 * administration-only (partner.create).
 */
@Service
@Profile("local-postgres")
public class PartnerManagementService {
  static final String CREATE = "partner.create", STORES = "partner.store.manage", PIN_RESET = "partner.pin.reset";
  private final PartnerRepository repository;
  private final TransactionTemplate tx;
  private final AdministrationAccess admin;
  private final ActorAccess actors;
  private final AccountDirectory directory;
  private final PartnerAccountProvisioning accounts;
  private final Clock clock;

  public PartnerManagementService(
      PartnerRepository repository,
      @Qualifier("partnerTransactions") TransactionTemplate tx,
      AdministrationAccess admin,
      ActorAccess actors,
      AccountDirectory directory,
      PartnerAccountProvisioning accounts,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.admin = admin;
    this.actors = actors;
    this.directory = directory;
    this.accounts = accounts;
    this.clock = clock;
  }

  // ---- administration ----

  /**
   * One page (default 20) of partners; the search matches the owner's name or login phone, or an
   * store name. Three queries per page regardless of size (no per-partner queries).
   */
  public List<PartnerSummary> list(UUID actor, UUID sid, String query, int offset, int limit) {
    return tx.execute(
        s -> {
          admin.requireAny(actor, sid, Set.of(CREATE, STORES));
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
                        o.storeNames(),
                        o.storeCount());
                  })
              .toList();
        });
  }

  public PartnerDetail detail(UUID actor, UUID sid, UUID partner) {
    return tx.execute(
        s -> {
          admin.requireAny(actor, sid, Set.of(CREATE, STORES));
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

  /**
   * Forgotten-PIN reset by SUPER_ADMIN or an ADMIN holding partner.pin.reset (partner.create alone
   * does not suffice). Every session of the partner is revoked; the new PIN is never echoed.
   */
  public void resetPin(UUID actor, UUID sid, UUID partner, String pin) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, PIN_RESET);
          var owner = owner(partner, true);
          accounts.resetPin(owner.accountId(), pin);
          repository.audit(owner.id(), actor, "PIN_RESET", clock.instant());
        });
  }

  public StoreCreatedResponse adminAddStore(UUID actor, UUID sid, UUID partner, StoreRequest request) {
    return tx.execute(
        s -> {
          admin.require(actor, sid, STORES);
          return addStore(actor, owner(partner, true), request);
        });
  }

  public void adminEditStore(
      UUID actor, UUID sid, UUID partner, UUID store, StoreRequest request) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, STORES);
          editStore(actor, owner(partner, true), store, request);
        });
  }

  public void adminStoreStatus(UUID actor, UUID sid, UUID partner, UUID store, String status) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, STORES);
          storeStatus(actor, owner(partner, true), store, status);
        });
  }

  public void adminStoreHours(
      UUID actor, UUID sid, UUID partner, UUID store, StoreHoursRequest request) {
    tx.executeWithoutResult(
        s -> {
          admin.require(actor, sid, STORES);
          storeHours(actor, owner(partner, true), store, request);
        });
  }

  // ---- owning partner ----

  public PartnerDetail profile(UUID actor, UUID sid) {
    return tx.execute(s -> detail(self(actor, sid)));
  }

  public StoreCreatedResponse ownAddStore(UUID actor, UUID sid, StoreRequest request) {
    return tx.execute(s -> addStore(actor, self(actor, sid), request));
  }

  public void ownEditStore(UUID actor, UUID sid, UUID store, StoreRequest request) {
    tx.executeWithoutResult(s -> editStore(actor, self(actor, sid), store, request));
  }

  public void ownStoreStatus(UUID actor, UUID sid, UUID store, String status) {
    tx.executeWithoutResult(s -> storeStatus(actor, self(actor, sid), store, status));
  }

  public void ownStoreHours(UUID actor, UUID sid, UUID store, StoreHoursRequest request) {
    tx.executeWithoutResult(s -> storeHours(actor, self(actor, sid), store, request));
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
        repository.storeDetails(owner.id()));
  }

  private StoreCreatedResponse addStore(UUID actor, Owner owner, StoreRequest request) {
    var data = validated(request);
    // A pending partner's extra stores are activated together with the partner.
    String status = "ACTIVE".equals(owner.status()) ? "ACTIVE" : "PENDING";
    UUID store = UUID.randomUUID();
    repository.insertStore(store, owner.id(), data, status, clock.instant());
    repository.audit(owner.id(), actor, "STORE_CREATED", clock.instant());
    return new StoreCreatedResponse(store, status);
  }

  private void editStore(UUID actor, Owner owner, UUID store, StoreRequest request) {
    if (repository.lockStore(store, owner.id()).isEmpty())
      throw error(Kind.NOT_FOUND, "STORE_NOT_FOUND");
    // Contact phone is independent of the login phone; this never touches credentials.
    repository.updateStore(store, validated(request));
    repository.audit(owner.id(), actor, "STORE_UPDATED", clock.instant());
  }

  /** Any store may be suspended; employees assigned only to suspended stores cannot log in. */
  private void storeStatus(UUID actor, Owner owner, UUID store, String status) {
    var current =
        repository
            .lockStore(store, owner.id())
            .orElseThrow(() -> error(Kind.NOT_FOUND, "STORE_NOT_FOUND"));
    if (current.equals(status)) return;
    if ("PENDING".equals(current))
      throw error(Kind.CONFLICT, "STORE_PENDING_ACTIVATION");
    repository.setStoreStatus(store, status);
    repository.audit(
        owner.id(),
        actor,
        "ACTIVE".equals(status) ? "STORE_REACTIVATED" : "STORE_SUSPENDED",
        clock.instant());
  }

  private void storeHours(UUID actor, Owner owner, UUID store, StoreHoursRequest request) {
    if (repository.lockStore(store, owner.id()).isEmpty())
      throw error(Kind.NOT_FOUND, "STORE_NOT_FOUND");
    final List<StoreHours> week;
    try {
      week =
          request.days().stream()
              .map(
                  d ->
                      new StoreHours(
                          d.weekday(),
                          d.closed(),
                          d.opensAt() == null ? null : LocalTime.parse(d.opensAt()),
                          d.closesAt() == null ? null : LocalTime.parse(d.closesAt())))
              .toList();
    } catch (java.time.format.DateTimeParseException ex) {
      throw error(Kind.INVALID, "INVALID_STORE_HOURS");
    }
    String failure = StoreHours.validateWeek(week);
    if (failure != null) throw error(Kind.INVALID, failure);
    repository.replaceHours(store, week);
    repository.audit(owner.id(), actor, "STORE_HOURS_CHANGED", clock.instant());
  }

  /** Normalizes optional text and checks references, map point and name. */
  private StoreData validated(StoreRequest request) {
    var data =
        new StoreData(
            request.name() == null ? null : request.name().strip(),
            request.categoryId(),
            request.cityId(),
            request.addressId(),
            optional(request.addressDetails()),
            optional(request.contactPhone()),
            request.latitude(),
            request.longitude(),
            optional(request.description()));
    String failure = data.validate();
    if (failure != null) throw error(Kind.INVALID, failure);
    requireCategory(data.categoryId());
    requireLocation(data.cityId(), data.addressId());
    return data;
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
