package com.jovx.api.membership.service;

import com.jovx.api.membership.api.MemberStanding;
import com.jovx.api.membership.model.MembershipModels.*;
import com.jovx.api.membership.repository.MembershipRepository;
import com.jovx.api.security.api.*;
import java.time.Clock;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** An expired member still logs in and sees a frozen balance (decision 55). */
@Service
@Profile("local-postgres")
public class MemberAccessPolicy implements LoginEligibility, AccountRoleConstraint, MemberStanding {
  private final MembershipRepository repository;
  private final Clock clock;

  public MemberAccessPolicy(MembershipRepository repository, Clock clock) {
    this.repository = repository; this.clock = clock;
  }

  public boolean supports(String role) {
    return "MEMBER".equals(role);
  }

  public boolean allowed(UUID account, String role) {
    return supports(role) && repository.isMember(account);
  }

  public boolean permits(UUID account, String role) {
    return "MEMBER".equals(role) || !repository.isMember(account);
  }

  public boolean termActive(UUID account) {
    return repository.member(account)
        .map(m -> Terms.state(clock.instant(), m.expiresAt()) == MembershipState.ACTIVE).orElse(false);
  }

  public boolean canTransact(UUID account) {
    return termActive(account)
        && repository.cardOf(account).map(c -> c.status() == CardStatus.ASSIGNED).orElse(false);
  }
}
