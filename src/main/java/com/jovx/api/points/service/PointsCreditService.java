package com.jovx.api.points.service;

import com.jovx.api.points.api.PointsCredits;
import com.jovx.api.points.api.PointsDebits;
import com.jovx.api.points.repository.PointsRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("local-postgres")
public class PointsCreditService implements PointsCredits, PointsDebits {
  private final PointsRepository repository;

  public PointsCreditService(PointsRepository repository) {
    this.repository = repository;
  }

  public void credit(UUID memberAccount, long points, String kind, UUID reference, Instant now) {
    if (points <= 0) return;
    long balance = repository.lockAccount(memberAccount, now) + points;
    repository.append(memberAccount, points, balance, kind, reference, now);
  }

  public boolean debit(UUID memberAccount, long points, String kind, UUID reference, Instant now) {
    if (points <= 0) return true;
    long balance = repository.lockAccount(memberAccount, now);
    if (balance < points) return false;
    repository.append(memberAccount, -points, balance - points, kind, reference, now);
    return true;
  }
}
