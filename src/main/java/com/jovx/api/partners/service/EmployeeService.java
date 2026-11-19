package com.jovx.api.partners.service;

import com.jovx.api.security.api.*;
import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.model.PartnerModels.*;
import com.jovx.api.partners.repository.PartnerRepository;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("local-postgres")
public class EmployeeService {
  private final PartnerRepository repository;
  private final TransactionTemplate tx;
  private final ActorAccess access;
  private final EmployeeAccounts accounts;
  private final AccountDirectory directory;
  private final Clock clock;

  public EmployeeService(
      PartnerRepository repository,
      @Qualifier("partnerTransactions") TransactionTemplate tx,
      ActorAccess access,
      EmployeeAccounts accounts,
      AccountDirectory directory,
      Clock clock) {
    this.repository = repository;
    this.tx = tx;
    this.access = access;
    this.accounts = accounts;
    this.directory = directory;
    this.clock = clock;
  }

  public List<EmployeeView> list(UUID actor, UUID sid) {
    return tx.execute(
        s -> {
          UUID owner = owner(actor, sid, "employee.create");
          var assignments = repository.employees(owner);
          var people =
              directory.summaries(assignments.stream().map(EmployeeAssignment::userId).toList());
          return assignments.stream()
              .map(
                  a -> {
                    var person = people.get(a.userId());
                    return new EmployeeView(
                        a.userId(),
                        person == null ? null : person.name(),
                        person == null ? null : person.phone(),
                        person == null ? null : person.status(),
                        a.branchIds(),
                        accounts.effectivePermissions(a.userId()));
                  })
              .sorted(
                  Comparator.comparing((EmployeeView e) -> e.name() == null ? "" : e.name())
                      .thenComparing(e -> e.userId().toString()))
              .toList();
        });
  }

  /** Suspension revokes the employee's sessions; reactivation needs a fresh login. */
  public void status(UUID actor, UUID sid, UUID employee, String status) {
    boolean active = "ACTIVE".equals(status);
    tx.executeWithoutResult(
        s -> {
          UUID owner = owner(actor, sid, active ? "employee.reactivate" : "employee.suspend");
          if (!repository.lockEmployee(employee, owner))
            throw error(Kind.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
          accounts.setActive(employee, active);
          repository.audit(
              owner, actor, active ? "EMPLOYEE_REACTIVATED" : "EMPLOYEE_SUSPENDED", clock.instant());
        });
  }

  /** Owning Partner only; the employee must log in again with the new PIN. */
  public void pin(UUID actor, UUID sid, UUID employee, String pin) {
    tx.executeWithoutResult(
        s -> {
          UUID owner = owner(actor, sid, "employee.pin.change");
          if (!repository.lockEmployee(employee, owner))
            throw error(Kind.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
          accounts.changePin(employee, pin);
          repository.audit(owner, actor, "EMPLOYEE_PIN_CHANGED", clock.instant());
        });
  }

  /** Replaces assigned branches; all must be active branches of the same Partner. */
  public void branches(UUID actor, UUID sid, UUID employee, Set<UUID> branchIds) {
    tx.executeWithoutResult(
        s -> {
          UUID owner = owner(actor, sid, "employee.create");
          if (!repository.lockEmployee(employee, owner))
            throw error(Kind.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
          for (UUID branch : branchIds)
            if (!repository.lockActiveBranch(branch, owner))
              throw error(Kind.INVALID, "INVALID_EMPLOYEE_BRANCH");
          repository.clearBranches(employee);
          for (UUID branch : branchIds) repository.assignBranch(employee, branch);
          repository.audit(owner, actor, "EMPLOYEE_BRANCHES_CHANGED", clock.instant());
        });
  }

  public EmployeeCreatedResponse create(UUID actor, UUID sid, CreateEmployeeRequest request) {
    return tx.execute(
        s -> {
          UUID owner = owner(actor, sid, "employee.create");
          if (!request.permissions().isEmpty())
            access.requirePermission(actor, sid, "employee.permissions.manage");
          for (UUID branch : request.branchIds())
            if (!repository.lockActiveBranch(branch, owner))
              throw error(Kind.INVALID, "INVALID_EMPLOYEE_BRANCH");
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
            throw error(Kind.NOT_FOUND, "EMPLOYEE_NOT_FOUND");
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
        .orElseThrow(() -> error(Kind.FORBIDDEN, "FORBIDDEN"))
        .id();
  }

  private static DomainException error(Kind status, String code) {
    return new DomainException(status, code);
  }
}
