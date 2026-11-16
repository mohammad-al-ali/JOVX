package com.jovx.api.partners.controller;

import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
@PreAuthorize("hasRole('PARTNER')")
public class EmployeeController {
  private final EmployeeService employees;

  public EmployeeController(EmployeeService employees) {
    this.employees = employees;
  }

  @PostMapping("/api/v1/partner/employees")
  @ResponseStatus(HttpStatus.CREATED)
  public EmployeeCreatedResponse create(
      JwtAuthenticationToken actor, @Valid @RequestBody CreateEmployeeRequest body) {
    return employees.create(id(actor), sid(actor), body);
  }

  @PutMapping("/api/v1/partner/employees/{userId}/permissions")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void permissions(
      JwtAuthenticationToken actor,
      @PathVariable UUID userId,
      @Valid @RequestBody EmployeePermissionsRequest body) {
    employees.permissions(id(actor), sid(actor), userId, body.permissions());
  }

  @GetMapping("/api/v1/partner/employee-permissions")
  public List<Map<String, Object>> catalog(JwtAuthenticationToken actor) {
    return employees.catalog(id(actor), sid(actor));
  }

  private static UUID id(JwtAuthenticationToken a) {
    return UUID.fromString(a.getName());
  }

  private static UUID sid(JwtAuthenticationToken a) {
    return UUID.fromString(a.getToken().getClaimAsString("sid"));
  }
}
