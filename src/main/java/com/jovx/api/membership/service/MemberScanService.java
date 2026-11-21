package com.jovx.api.membership.service;

import com.jovx.api.membership.api.MemberScans;
import com.jovx.api.membership.model.MembershipModels.*;
import com.jovx.api.membership.repository.MembershipRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Resolves a scanned QR or printed card for the point of sale, inside the caller's transaction. */
@Service
@Profile("local-postgres")
public class MemberScanService implements MemberScans {
  private final MembershipRepository repository;
  private final Clock clock;

  public MemberScanService(MembershipRepository repository, Clock clock) {
    this.repository = repository; this.clock = clock;
  }

  public Optional<ScannedMember> consumeQr(String token, Instant now) {
    if (token == null || !token.startsWith(QR_PREFIX) || token.length() > 100) return Optional.empty();
    return repository.consumeScanToken(MemberSelfService.digest(token), now)
        .flatMap(ids -> repository.card(ids[1])).flatMap(this::scanned);
  }

  public Optional<ScannedMember> byCard(String cardCode) {
    String code = Terms.cardCode(cardCode);
    return code == null ? Optional.empty() : repository.cardByCode(code).flatMap(this::scanned);
  }

  public Optional<ScannedMember> byCardId(UUID cardId) {
    return repository.card(cardId).flatMap(this::scanned);
  }

  private Optional<ScannedMember> scanned(Card card) {
    if (card.member() == null) return Optional.empty();
    return repository.member(card.member()).map(m -> new ScannedMember(
        m.accountId(), card.id(), m.memberNumber(), Terms.maskedCode(card.code()),
        Terms.state(clock.instant(), m.expiresAt()) == MembershipState.ACTIVE,
        card.status() == CardStatus.ASSIGNED));
  }
}
