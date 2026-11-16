package com.jovx.api.partners.service;

import com.jovx.api.security.api.*;
import com.jovx.api.partners.dto.PartnerDtos.*;
import com.jovx.api.partners.model.PartnerModels.*;
import com.jovx.api.partners.repository.PartnerRepository;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-postgres")
public class PartnerAccessPolicy implements LoginEligibility, AccountRoleConstraint {
  private final PartnerRepository repository;

  public PartnerAccessPolicy(PartnerRepository repository) {
    this.repository = repository;
  }

  public boolean supports(String role) {
    return "PARTNER".equals(role) || "PARTNER_EMPLOYEE".equals(role);
  }

  public boolean allowed(UUID account, String role) {
    return "PARTNER_EMPLOYEE".equals(role)
        ? repository.employeeCanLogin(account)
        : supports(role) && repository.ownerCanLogin(account);
  }

  public boolean permits(UUID account, String role) {
    return (!repository.hasOwnerAccount(account) || "PARTNER".equals(role))
        && (!repository.hasEmployeeAccount(account) || "PARTNER_EMPLOYEE".equals(role));
  }
}
