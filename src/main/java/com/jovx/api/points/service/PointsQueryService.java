package com.jovx.api.points.service;

import com.jovx.api.membership.api.MemberStanding;
import com.jovx.api.points.repository.PointsRepository;
import com.jovx.api.points.repository.PointsRepository.Entry;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-postgres")
public class PointsQueryService {
  private final PointsRepository repository;
  private final MemberStanding standing;

  public PointsQueryService(PointsRepository repository, MemberStanding standing) {
    this.repository = repository; this.standing = standing;
  }

  /** The balance is shown in full; it is frozen (not spendable) after the term ends (decision 55). */
  public Balance balance(UUID member) {
    return new Balance(repository.balance(member), !standing.termActive(member));
  }

  public List<Entry> history(UUID member, int offset, int limit) {
    return repository.history(member, offset, limit);
  }

  public record Balance(long balance, boolean frozen) {}
}
