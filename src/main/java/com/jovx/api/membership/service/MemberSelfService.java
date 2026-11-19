package com.jovx.api.membership.service;

import static com.jovx.api.membership.service.MembershipAdminService.fail;

import com.jovx.api.membership.dto.MembershipDtos.*;
import com.jovx.api.membership.model.MembershipModels.*;
import com.jovx.api.membership.repository.MembershipRepository;
import com.jovx.api.security.api.*;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** The member's own use cases, and the public first-PIN claim with the physical card. */
@Service
@Profile("local-postgres")
public class MemberSelfService {
  /** One answer for an unknown card and a phone mismatch, so neither can be probed. */
  static final String CLAIM_REJECTED = "CARD_CLAIM_REJECTED";
  private static final SecureRandom RANDOM = new SecureRandom();
  private final MembershipRepository repository;
  private final MemberAccounts accounts;
  private final AccountDirectory directory;
  private final ActorAccess actors;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MemberSelfService(
      MembershipRepository repository, MemberAccounts accounts, AccountDirectory directory, ActorAccess actors,
      @Qualifier("membershipTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.accounts = accounts; this.directory = directory;
    this.actors = actors; this.tx = tx; this.clock = clock;
  }

  public void claim(ClaimRequest r) {
    tx.executeWithoutResult(s -> {
      String code = Terms.cardCode(r.cardCode());
      if (code == null) throw fail(HttpStatus.BAD_REQUEST, CLAIM_REJECTED);
      Card card = repository.cardByCodeForUpdate(code).orElseThrow(() -> fail(HttpStatus.BAD_REQUEST, CLAIM_REJECTED));
      if (card.member() == null || !accounts.phoneMatches(card.member(), r.phone()))
        throw fail(HttpStatus.BAD_REQUEST, CLAIM_REJECTED);
      if (card.status() == CardStatus.SUSPENDED) throw fail(HttpStatus.CONFLICT, "CARD_SUSPENDED");
      if (!accounts.unclaimed(card.member())) throw fail(HttpStatus.CONFLICT, "ACCOUNT_ALREADY_CLAIMED");
      accounts.claim(card.member(), r.pin());
      repository.event(card.member(), card.member(), "ACCOUNT_CLAIMED", clock.instant());
    });
  }

  public List<PlanView> publicPlans() {
    return repository.plans(true).stream().map(PlanView::of).toList();
  }

  public MemberProfile profile(UUID actor, UUID sid) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "MEMBER");
      Member m = repository.member(actor).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
      var who = directory.summaries(List.of(actor)).get(actor);
      var card = repository.cardOf(actor);
      var state = Terms.state(clock.instant(), m.expiresAt());
      boolean qr = state == MembershipState.ACTIVE && card.map(c -> c.status() == CardStatus.ASSIGNED).orElse(false);
      return new MemberProfile(actor, m.memberNumber(), who == null ? "" : who.name(),
          repository.plan(m.planId()).map(Plan::name).orElse(""), m.startsAt(), m.expiresAt(), state,
          card.map(c -> new MemberCardView(Terms.maskedCode(c.code()), c.status())).orElse(null), qr);
    });
  }

  /** A fresh 60-second QR token; any earlier unused token of the member stops working. */
  public ScanToken scanToken(UUID actor, UUID sid) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "MEMBER");
      Member m = repository.memberForUpdate(actor).orElseThrow(() -> fail(HttpStatus.NOT_FOUND, "MEMBER_NOT_FOUND"));
      Instant now = clock.instant();
      if (Terms.state(now, m.expiresAt()) != MembershipState.ACTIVE) throw fail(HttpStatus.CONFLICT, "MEMBERSHIP_EXPIRED");
      Card card = repository.cardOf(actor).filter(c -> c.status() == CardStatus.ASSIGNED)
          .orElseThrow(() -> fail(HttpStatus.CONFLICT, "CARD_NOT_ACTIVE"));
      byte[] secret = new byte[32];
      RANDOM.nextBytes(secret);
      String token = "JX2." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
      Instant expires = now.plus(com.jovx.api.membership.model.MembershipModels.SCAN_TOKEN_LIFETIME);
      repository.replaceScanToken(digest(token), actor, card.id(), now, expires);
      return new ScanToken(token, expires);
    });
  }

  static String digest(String token) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
