package com.jovx.api.partners.service;

import com.jovx.api.security.api.*;
import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.model.PartnerModels.*;
import com.jovx.api.partners.repository.PartnerRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("local-postgres")
public class PartnerService {
  private final PartnerRepository repository;
  private final TransactionTemplate tx;
  private final PartnerAccountProvisioning accounts;
  private final AdministrationAccess access;
  private final Clock clock;

  public PartnerService(
      PartnerRepository repository,
      @Qualifier("partnerTransactions") TransactionTemplate tx,
      PartnerAccountProvisioning accounts,
      AdministrationAccess access,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.accounts = accounts;
    this.access = access;
    this.clock = clock;
  }

  public PartnerCreatedResponse create(UUID actor, UUID session, RegisterPartnerRequest request) {
    return tx.execute(
        s -> {
          access.require(actor, session, "partner.create");
          new PartnerDiscount(request.discountPercent());
          if (request.discountPercent().stripTrailingZeros().scale() > 2)
            throw error(HttpStatus.BAD_REQUEST, "INVALID_DISCOUNT_PRECISION");
          if (!repository.categoryExists(request.categoryId())
              || !repository.addressBelongsToCity(request.addressId(), request.cityId()))
            throw error(HttpStatus.BAD_REQUEST, "INVALID_LOCATION_OR_CATEGORY");
          UUID account = accounts.createPending(request.userName(), request.phone(), request.pin());
          UUID partner = UUID.randomUUID(),
              establishment = UUID.randomUUID(),
              branch = UUID.randomUUID();
          repository.insertOwner(partner, account, request.discountPercent());
          repository.insertEstablishment(
              establishment,
              partner,
              request.categoryId(),
              request.name().strip(),
              optional(request.contactPhone()));
          repository.insertBranch(
              branch,
              establishment,
              request.branchName().strip(),
              request.cityId(),
              request.addressId(),
              optional(request.addressDetails()));
          repository.audit(partner, actor, "PARTNER_CREATED", clock.instant());
          return new PartnerCreatedResponse(partner, account, establishment, branch, "PENDING");
        });
  }

  public void activate(UUID actor, UUID session, UUID partner) {
    tx.executeWithoutResult(
        s -> {
          access.require(actor, session, "partner.create");
          var owner =
              repository
                  .lockOwner(partner)
                  .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PARTNER_NOT_FOUND"));
          if ("ACTIVE".equals(owner.status())) return;
          if (!"PENDING".equals(owner.status()))
            throw error(HttpStatus.CONFLICT, "PARTNER_NOT_PENDING");
          if (repository.establishmentCount(partner) != 1)
            throw error(HttpStatus.CONFLICT, "INITIAL_REGISTRATION_REQUIRED");
          accounts.activate(owner.accountId());
          repository.activateRegistration(partner);
          repository.audit(partner, actor, "PARTNER_ACTIVATED", clock.instant());
        });
  }

  public PartnerContextResponse context(UUID account) {
    var owner =
        repository
            .activeOwner(account, false)
            .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "FORBIDDEN"));
    return new PartnerContextResponse(
        owner.id(), owner.minimum(), repository.establishments(owner.id()));
  }

  public ReferenceDataResponse referenceData() {
    return new ReferenceDataResponse(
        repository.cities(), repository.addresses(), repository.categories());
  }

  public Reference city(UUID actor, UUID session, String name) {
    return tx.execute(
        s -> {
          access.require(actor, session, "partner.create");
          return repository.saveCity(referenceName(name));
        });
  }

  public Reference category(UUID actor, UUID session, String name) {
    return tx.execute(
        s -> {
          access.require(actor, session, "partner.create");
          return repository.saveCategory(referenceName(name));
        });
  }

  public Address address(UUID actor, UUID session, UUID city, String name) {
    return tx.execute(
        s -> {
          access.require(actor, session, "partner.create");
          if (!repository.lockCity(city)) throw error(HttpStatus.NOT_FOUND, "CITY_NOT_FOUND");
          return repository.saveAddress(city, referenceName(name));
        });
  }

  private static String referenceName(String name) {
    if (name == null) throw error(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE_NAME");
    String normalized = name.replaceAll("(?U)\\s+", " ").strip();
    if (normalized.isEmpty()
        || normalized.length() > 200
        || normalized.codePoints().anyMatch(Character::isISOControl))
      throw error(HttpStatus.BAD_REQUEST, "INVALID_REFERENCE_NAME");
    return normalized;
  }

  private static String optional(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static ResponseStatusException error(HttpStatus status, String code) {
    return new ResponseStatusException(status, code);
  }
}
