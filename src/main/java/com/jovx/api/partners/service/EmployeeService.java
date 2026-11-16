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
public class EmployeeService {
  private final PartnerRepository repository;
  private final TransactionTemplate tx;
  private final ActorAccess access;
  private final EmployeeAccounts accounts;
  private final Clock clock;

  public EmployeeService(
      PartnerRepository repository,
      @Qualifier("partnerTransactions") TransactionTemplate tx,
      ActorAccess access,
      EmployeeAccounts accounts,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.access = access;
    this.accounts = accounts;
    this.clock = clock;
  }

  public EmployeeCreatedResponse create(UUID actor, UUID sid, CreateEmployeeRequest request) {
    return tx.execute(
        s -> {
          UUID owner = owner(actor, sid, "employee.create");
          if (!request.permissions().isEmpty())
            access.requirePermission(actor, sid, "employee.permissions.manage");
          for (UUID branch : request.branchIds())
            if (!repository.lockActiveBranch(branch, owner))
              throw error(HttpStatus.BAD_REQUEST, "INVALID_EMPLOYEE_BRANCH");
          UUID employee = accounts.create(request.name(), request.phone(), request.pin());
          repository.insertEmployee(employee, owner);
          for (UUID branch : request.branchIds()) repository.assignBranch(employee, branch);
          accounts.replacePermissions(actor, employee, request.permissions());
          repository.audit(owner, actor, "EMPLOYEE_CREATED", clock.instant());
          return new EmployeeCreatedResponse(
              employee,
              "PARTNER_EMPLOYEE",
              "ACTIVE",
              "PARTNER",
              owner,
              Set.copyOf(request.branchIds()),
              Set.copyOf(request.permissions()));
        });
  }

  public void permissions(UUID actor, UUID sid, UUID employee, Set<String> permissions) {
    tx.executeWithoutResult(
        s -> {
          UUID owner = owner(actor, sid, "employee.permissions.manage");
          if (!repository.lockEmployee(employee, owner))
            throw error(HttpStatus.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
          accounts.replacePermissions(actor, employee, permissions);
          repository.audit(owner, actor, "EMPLOYEE_PERMISSIONS_CHANGED", clock.instant());
        });
  }

  public List<Map<String, Object>> catalog(UUID actor, UUID sid) {
    return tx.execute(
        s -> {
          owner(actor, sid, "employee.permissions.manage");
          return accounts.catalog(actor);
        });
  }

  private UUID owner(UUID actor, UUID sid, String permission) {
    access.requireRole(actor, sid, "PARTNER");
    access.requirePermission(actor, sid, permission);
    return repository
        .activeOwner(actor, true)
        .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "FORBIDDEN"))
        .id();
  }

  private static ResponseStatusException error(HttpStatus status, String code) {
    return new ResponseStatusException(status, code);
  }
}
