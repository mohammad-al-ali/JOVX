package com.jovx.api.membership.service;

import com.jovx.api.membership.dto.MembershipDtos.*;
import com.jovx.api.membership.model.MembershipModels.*;
import com.jovx.api.membership.repository.MembershipRepository;
import com.jovx.api.security.api.*;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Administration use cases; every command re-checks member.manage inside its transaction. */
@Service
@Profile("local-postgres")
public class MembershipAdminService {
  static final String PERMISSION = "member.manage";
  /** Batch label of cards recorded at the moment of sale, without prior stock registration. */
  static final String DIRECT_SALE_BATCH = "بيع مباشر";
  private final MembershipRepository repository;
  private final AdministrationAccess access;
  private final MemberAccounts accounts;
  private final AccountDirectory directory;
  private final TransactionTemplate tx;
  private final Clock clock;

  public MembershipAdminService(
      MembershipRepository repository, AdministrationAccess access, MemberAccounts accounts,
      AccountDirectory directory, @Qualifier("membershipTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.access = access; this.accounts = accounts;
    this.directory = directory; this.tx = tx; this.clock = clock;
  }

  // ---- plans ----

  public List<PlanView> plans(UUID actor, UUID sid) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      return repository.plans(false).stream().map(PlanView::of).toList();
    });
  }

  public PlanView createPlan(UUID actor, UUID sid, PlanRequest r) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      var plan = plan(UUID.randomUUID(), r);
      try { repository.insertPlan(plan, clock.instant()); }
      catch (DuplicateKeyException ex) { throw fail(Kind.CONFLICT, "PLAN_NAME_EXISTS"); }
      return PlanView.of(plan);
    });
  }

  public PlanView updatePlan(UUID actor, UUID sid, UUID id, PlanRequest r) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      var plan = plan(id, r);
      try { if (!repository.updatePlan(plan)) throw fail(Kind.NOT_FOUND, "PLAN_NOT_FOUND"); }
      catch (DuplicateKeyException ex) { throw fail(Kind.CONFLICT, "PLAN_NAME_EXISTS"); }
      return PlanView.of(plan);
    });
  }

  private static Plan plan(UUID id, PlanRequest r) {
    String name = r.name().strip().replaceAll("\\s+", " ");
    if (name.isEmpty()) throw fail(Kind.INVALID, "INVALID_REQUEST");
    return new Plan(id, name, r.priceAmount(), r.currency(), r.durationMonths(), r.familySlots(), r.active());
  }

  // ---- card stock ----

  public CardBatchResult registerCards(UUID actor, UUID sid, CardBatchRequest r) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      String batch = r.batchLabel().strip();
      if (batch.isEmpty()) throw fail(Kind.INVALID, "INVALID_REQUEST");
      var invalid = new ArrayList<String>();
      var existing = new ArrayList<String>();
      var seen = new LinkedHashSet<String>();
      int added = 0;
      Instant now = clock.instant();
      for (String raw : r.codes()) {
        String code = Terms.cardCode(raw);
        if (code == null) { if (raw != null && !raw.isBlank()) invalid.add(raw.strip()); continue; }
        if (!seen.add(code)) continue;
        if (repository.insertCard(code, batch, actor, now)) added++; else existing.add(code);
      }
      return new CardBatchResult(added, existing, invalid);
    });
  }

  public CardStock cards(UUID actor, UUID sid, CardStatus status, String query) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      return new CardStock(repository.cardCounts(),
          repository.cards(status, query, 200).stream().map(CardView::of).toList());
    });
  }

  // ---- sale and renewal ----

  public MembershipTermResult sell(UUID actor, UUID sid, SellMembershipRequest r) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      String code = Terms.cardCode(r.cardCode());
      if (code == null) throw fail(Kind.INVALID, "INVALID_CARD_CODE");
      // Concurrent retries of one form wait here, then see the committed payment and replay it.
      repository.lockRequest(r.requestId());
      var replay = repository.paymentByRequest(r.requestId());
      if (replay.isPresent()) {
        var p = replay.get();
        boolean same = p.kind() == PaymentKind.SALE && p.planId().equals(r.planId())
            && p.method() == r.payment().method()
            && repository.cardOf(p.member()).map(c -> c.code().equals(code)).orElse(false)
            && accounts.phoneMatches(p.member(), r.phone());
        if (!same) throw fail(Kind.CONFLICT, "REQUEST_ID_REUSED");
        return replayed(p, PaymentKind.SALE);
      }
      Plan plan = activePlan(r.planId());
      Instant now = clock.instant();
      // Decision 69: a card not registered in stock is recorded at the moment of sale.
      // ON CONFLICT DO NOTHING + the row lock below keep concurrent sales of one card safe.
      repository.insertCard(code, DIRECT_SALE_BATCH, actor, now);
      Card card = repository.cardByCodeForUpdate(code).orElseThrow();
      if (card.status() != CardStatus.IN_STOCK) throw fail(Kind.CONFLICT, "CARD_NOT_AVAILABLE");
      final UUID member;
      try { member = accounts.createUnclaimed(r.name(), r.phone()); }
      catch (MemberAccounts.PhoneAlreadyRegistered ex) { throw fail(Kind.CONFLICT, "PHONE_ALREADY_REGISTERED"); }
      Instant expires = Terms.end(now, plan.durationMonths());
      String number = Terms.memberNumber(Long.parseLong(repository.nextMemberNumberValue()));
      repository.insertMember(new Member(member, number, plan.id(), now, expires), actor, now);
      repository.assignCard(card.id(), member, now);
      var payment = payment(r.requestId(), member, plan, PaymentKind.SALE, r.payment(), null, expires, actor, now);
      repository.insertPayment(payment);
      repository.event(member, actor, "MEMBERSHIP_SOLD", now);
      return new MembershipTermResult(member, number, expires, view(payment, plan.name(), Map.of()));
    });
  }

  public MembershipTermResult renew(UUID actor, UUID sid, UUID memberId, RenewMembershipRequest r) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      repository.lockRequest(r.requestId());
      var replay = repository.paymentByRequest(r.requestId());
      if (replay.isPresent()) {
        var p = replay.get();
        // The same request id with a different member, plan or method is a different operation.
        if (!p.member().equals(memberId) || !p.planId().equals(r.planId()) || p.method() != r.payment().method())
          throw fail(Kind.CONFLICT, "REQUEST_ID_REUSED");
        return replayed(p, PaymentKind.RENEWAL);
      }
      Member member = repository.memberForUpdate(memberId).orElseThrow(() -> fail(Kind.NOT_FOUND, "MEMBER_NOT_FOUND"));
      Plan plan = activePlan(r.planId());
      Instant now = clock.instant();
      Instant expires = Terms.end(Terms.renewalStart(now, member.expiresAt()), plan.durationMonths());
      repository.updateTerm(memberId, plan.id(), expires);
      var payment = payment(r.requestId(), memberId, plan, PaymentKind.RENEWAL, r.payment(), member.expiresAt(), expires, actor, now);
      repository.insertPayment(payment);
      repository.event(memberId, actor, "MEMBERSHIP_RENEWED", now);
      return new MembershipTermResult(memberId, member.memberNumber(), expires, view(payment, plan.name(), Map.of()));
    });
  }

  private MembershipTermResult replayed(Payment p, PaymentKind expected) {
    if (p.kind() != expected) throw fail(Kind.CONFLICT, "REQUEST_ID_REUSED");
    Member m = repository.member(p.member()).orElseThrow();
    String planName = repository.plan(p.planId()).map(Plan::name).orElse("");
    return new MembershipTermResult(m.accountId(), m.memberNumber(), p.newExpiresAt(), view(p, planName, Map.of()));
  }

  private Plan activePlan(UUID id) {
    Plan plan = repository.plan(id).orElseThrow(() -> fail(Kind.NOT_FOUND, "PLAN_NOT_FOUND"));
    if (!plan.active()) throw fail(Kind.CONFLICT, "PLAN_INACTIVE");
    return plan;
  }

  /** The amount is the plan's price at the time of payment; the seller does not type it. */
  private static Payment payment(UUID request, UUID member, Plan plan, PaymentKind kind, PaymentRequest r,
      Instant previous, Instant expires, UUID actor, Instant now) {
    String reference = r.reference() == null || r.reference().isBlank() ? null : r.reference().strip();
    return new Payment(UUID.randomUUID(), request, member, plan.id(), kind, plan.priceAmount(), plan.currency(),
        r.method(), reference, previous, expires, actor, now);
  }

  // ---- card suspension and PIN reset ----

  public void suspendCard(UUID actor, UUID sid, UUID memberId) {
    tx.executeWithoutResult(s -> {
      access.require(actor, sid, PERMISSION);
      repository.memberForUpdate(memberId).orElseThrow(() -> fail(Kind.NOT_FOUND, "MEMBER_NOT_FOUND"));
      Instant now = clock.instant();
      if (!repository.suspendCard(memberId, now)) throw fail(Kind.CONFLICT, "CARD_NOT_ACTIVE");
      repository.dropScanTokens(memberId);
      repository.event(memberId, actor, "CARD_SUSPENDED", now);
    });
  }

  public void resetPin(UUID actor, UUID sid, UUID memberId, String pin) {
    tx.executeWithoutResult(s -> {
      access.require(actor, sid, PERMISSION);
      repository.memberForUpdate(memberId).orElseThrow(() -> fail(Kind.NOT_FOUND, "MEMBER_NOT_FOUND"));
      accounts.resetPin(memberId, pin);
      repository.event(memberId, actor, "PIN_RESET", clock.instant());
    });
  }

  // ---- reads ----

  public List<MemberSummary> search(UUID actor, UUID sid, String query) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      List<Member> found;
      if (query == null || query.isBlank()) found = repository.recentMembers(50);
      else {
        var byNumber = repository.membersByNumber(query.strip(), 50);
        var byAccount = repository.members(directory.search("MEMBER", query, 50));
        var merged = new LinkedHashMap<UUID, Member>();
        for (var m : byNumber) merged.put(m.accountId(), m);
        for (var m : byAccount) merged.putIfAbsent(m.accountId(), m);
        found = new ArrayList<>(merged.values());
      }
      var ids = found.stream().map(Member::accountId).toList();
      var people = directory.summaries(ids);
      var cards = repository.cardsOf(ids);
      var plans = planNames();
      Instant now = clock.instant();
      return found.stream().map(m -> {
        var who = people.get(m.accountId());
        var card = cards.get(m.accountId());
        return new MemberSummary(m.accountId(), m.memberNumber(), who == null ? "" : who.name(),
            who == null ? "" : who.phone(), plans.getOrDefault(m.planId(), ""), m.expiresAt(),
            Terms.state(now, m.expiresAt()), who != null && !"PENDING".equals(who.status()),
            card == null ? null : card.status());
      }).toList();
    });
  }

  public MemberDetail detail(UUID actor, UUID sid, UUID memberId) {
    return tx.execute(s -> {
      access.require(actor, sid, PERMISSION);
      Member m = repository.member(memberId).orElseThrow(() -> fail(Kind.NOT_FOUND, "MEMBER_NOT_FOUND"));
      var payments = repository.payments(memberId);
      var people = new HashSet<UUID>();
      people.add(memberId);
      payments.forEach(p -> people.add(p.recordedBy()));
      var names = directory.summaries(people);
      var who = names.get(memberId);
      var plans = planNames();
      return new MemberDetail(m.accountId(), m.memberNumber(), who == null ? "" : who.name(),
          who == null ? "" : who.phone(), m.planId(), plans.getOrDefault(m.planId(), ""), m.startsAt(), m.expiresAt(),
          Terms.state(clock.instant(), m.expiresAt()), who != null && !"PENDING".equals(who.status()),
          repository.cardOf(memberId).map(CardView::of).orElse(null),
          payments.stream().map(p -> view(p, plans.getOrDefault(p.planId(), ""), names)).toList());
    });
  }

  private Map<UUID, String> planNames() {
    var result = new HashMap<UUID, String>();
    repository.plans(false).forEach(p -> result.put(p.id(), p.name()));
    return result;
  }

  private static PaymentView view(Payment p, String planName, Map<UUID, AccountDirectory.AccountSummary> names) {
    var by = names.get(p.recordedBy());
    return new PaymentView(p.id(), p.kind(), planName, p.amount(), p.currency(), p.method(), p.reference(),
        p.previousExpiresAt(), p.newExpiresAt(), p.recordedBy(), by == null ? null : by.name(), p.createdAt());
  }

  private static DomainException fail(Kind status, String code) {
    return new DomainException(status, code);
  }
}
