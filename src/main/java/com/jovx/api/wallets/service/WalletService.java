package com.jovx.api.wallets.service;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.partners.api.PartnerLookup;
import com.jovx.api.partners.api.PartnerLookup.PartnerRef;
import com.jovx.api.security.api.*;
import com.jovx.api.wallets.dto.WalletDtos.*;
import com.jovx.api.wallets.model.WalletModels.*;
import com.jovx.api.wallets.repository.WalletRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wallet use cases for the owning partner, its employees and the administration. Every
 * command re-checks the actor and locks the wallet row inside one transaction.
 */
@Service
@Profile("local-postgres")
public class WalletService {
  static final String ADMIN_PERMISSION = "wallet.manage";
  private static final int RECENT = 50;
  private final WalletRepository repository;
  private final PartnerLookup partners;
  private final ActorAccess actors;
  private final AdministrationAccess administration;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final WalletEvents events;

  public WalletService(
      WalletRepository repository, PartnerLookup partners, ActorAccess actors, AdministrationAccess administration,
      @Qualifier("walletTransactions") TransactionTemplate tx, Clock clock, WalletEvents events) {
    this.repository = repository; this.partners = partners; this.actors = actors;
    this.administration = administration; this.tx = tx; this.clock = clock; this.events = events;
  }

  /** Who is acting for which partner, and whether they may request a top-up or refund. */
  private record PartnerActor(PartnerRef partner, boolean owner, boolean mayTopup) {}

  private PartnerActor partnerActor(UUID actor, UUID sid, String role) {
    if ("PARTNER".equals(role)) {
      actors.requireRole(actor, sid, "PARTNER");
      return new PartnerActor(partners.ownedBy(actor).orElseThrow(() -> fail(Kind.FORBIDDEN, "FORBIDDEN")), true, true);
    }
    actors.requirePermission(actor, sid, "wallet.read");
    var partner = partners.employerOf(actor).orElseThrow(() -> fail(Kind.FORBIDDEN, "FORBIDDEN"));
    boolean topup;
    try { actors.requirePermission(actor, sid, "wallet.topup.request"); topup = true; }
    catch (RuntimeException denied) { topup = false; }
    return new PartnerActor(partner, false, topup);
  }

  // ---- partner side ----

  public WalletView ownWallet(UUID actor, UUID sid, String role) {
    return tx.execute(s -> {
      var a = partnerActor(actor, sid, role);
      return view(a.partner(), a.mayTopup(), a.owner());
    });
  }

  public RequestView requestTopup(UUID actor, UUID sid, String role, TopupRequest r) {
    return tx.execute(s -> {
      var a = partnerActor(actor, sid, role);
      if (!a.mayTopup()) throw fail(Kind.FORBIDDEN, "FORBIDDEN");
      var replay = replay(r.requestId(), a.partner().id(), RequestKind.TOPUP);
      if (replay != null) return replay;
      requireActive(a.partner());
      requireLimits(r.amount());
      var q = newRequest(r.requestId(), a.partner().id(), RequestKind.TOPUP, r.amount(), r.method(), r.reference(), r.note(), actor);
      repository.insertRequest(q);
      return RequestView.of(q, a.partner().name());
    });
  }

  /** Decision 21: the points are held at once and leave the spendable balance. */
  public RequestView requestRefund(UUID actor, UUID sid, String role, RefundRequest r) {
    return tx.execute(s -> {
      var a = partnerActor(actor, sid, role);
      if (!a.owner()) throw fail(Kind.FORBIDDEN, "FORBIDDEN");
      var replay = replay(r.requestId(), a.partner().id(), RequestKind.REFUND);
      if (replay != null) return replay;
      requireActive(a.partner());
      Instant now = clock.instant();
      var wallet = repository.lockWallet(a.partner().id(), now);
      if (!Rules.refundable(r.amount(), wallet)) throw fail(Kind.CONFLICT, "INSUFFICIENT_BALANCE");
      var q = newRequest(r.requestId(), a.partner().id(), RequestKind.REFUND, r.amount(), r.method(), r.reference(), r.note(), actor);
      repository.insertRequest(q);
      repository.move(wallet, -r.amount(), r.amount(), LedgerKind.REFUND_HELD, q.id(), actor, now);
      return RequestView.of(q, a.partner().name());
    });
  }

  // ---- administration ----

  public List<WalletSummary> summaries(UUID actor, UUID sid) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      var wallets = repository.wallets();
      var pending = repository.pendingCounts();
      var settings = repository.settings();
      return partners.all().stream().filter(p -> !"PENDING".equals(p.status())).map(p -> {
        var w = wallets.getOrDefault(p.id(), new Wallet(p.id(), 0, 0));
        return new WalletSummary(p.id(), p.name(), p.status(), w.balance(), w.held(), Rules.low(w.balance(), settings),
            pending.getOrDefault(p.id(), 0));
      }).toList();
    });
  }

  public List<RequestView> requests(UUID actor, UUID sid, RequestStatus status) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      var names = new HashMap<UUID, String>();
      partners.all().forEach(p -> names.put(p.id(), p.name()));
      return repository.requests(null, status, 200).stream().map(q -> RequestView.of(q, names.getOrDefault(q.partnerId(), ""))).toList();
    });
  }

  public WalletView partnerWallet(UUID actor, UUID sid, UUID partnerId) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      return view(partner(partnerId), false, false);
    });
  }

  public RequestView approve(UUID actor, UUID sid, UUID requestId, ApproveRequest r) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      var q = pending(requestId);
      var partner = partner(q.partnerId());
      Instant now = clock.instant();
      var wallet = repository.lockWallet(q.partnerId(), now);
      if (q.kind() == RequestKind.TOPUP) {
        requireActive(partner);
        repository.decide(q.id(), RequestStatus.APPROVED, actor, now, null, null, null);
        repository.move(wallet, q.amount(), 0, LedgerKind.TOPUP_APPROVED, q.id(), actor, now);
      } else {
        if (r == null || r.payoutMethod() == null) throw fail(Kind.INVALID, "PAYOUT_METHOD_REQUIRED");
        repository.decide(q.id(), RequestStatus.APPROVED, actor, now, null, r.payoutMethod(), blankToNull(r.payoutReference()));
        // The held points leave the wallet; the spendable balance does not change again.
        repository.move(wallet, 0, -q.amount(), LedgerKind.REFUND_HELD, q.id(), actor, now);
      }
      events.decided(q, true, null, now);
      return RequestView.of(repository.lockRequest(q.id()).orElseThrow(), partner.name());
    });
  }

  public RequestView reject(UUID actor, UUID sid, UUID requestId, String reason) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      var q = pending(requestId);
      var partner = partner(q.partnerId());
      Instant now = clock.instant();
      repository.decide(q.id(), RequestStatus.REJECTED, actor, now, reason.strip(), null, null);
      if (q.kind() == RequestKind.REFUND) {
        var wallet = repository.lockWallet(q.partnerId(), now);
        repository.move(wallet, q.amount(), -q.amount(), LedgerKind.REFUND_RELEASED, q.id(), actor, now);
      }
      events.decided(q, false, reason.strip(), now);
      return RequestView.of(repository.lockRequest(q.id()).orElseThrow(), partner.name());
    });
  }

  /** Decision 22: direct top-up for active partners only; recorded as an approved request. */
  public RequestView directTopup(UUID actor, UUID sid, UUID partnerId, TopupRequest r) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      var replay = replay(r.requestId(), partnerId, RequestKind.TOPUP);
      if (replay != null) return replay;
      var partner = partner(partnerId);
      requireActive(partner);
      requireLimits(r.amount());
      Instant now = clock.instant();
      var wallet = repository.lockWallet(partnerId, now);
      var q = newRequest(r.requestId(), partnerId, RequestKind.TOPUP, r.amount(), r.method(), r.reference(), r.note(), actor);
      repository.insertRequest(q);
      repository.decide(q.id(), RequestStatus.APPROVED, actor, now, null, null, null);
      repository.move(wallet, r.amount(), 0, LedgerKind.DIRECT_TOPUP, q.id(), actor, now);
      return RequestView.of(repository.lockRequest(q.id()).orElseThrow(), partner.name());
    });
  }

  public SettingsView settings(UUID actor, UUID sid) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      return SettingsView.of(repository.settings());
    });
  }

  public SettingsView updateSettings(UUID actor, UUID sid, SettingsRequest r) {
    return tx.execute(s -> {
      administration.require(actor, sid, ADMIN_PERMISSION);
      if (!Rules.validLimits(r.minTopup(), r.maxTopup(), r.lowBalanceThreshold())) throw fail(Kind.INVALID, "INVALID_WALLET_LIMITS");
      var settings = new Settings(r.minTopup(), r.maxTopup(), r.lowBalanceThreshold());
      repository.updateSettings(settings, actor, clock.instant());
      return SettingsView.of(settings);
    });
  }

  // ---- helpers ----

  private WalletView view(PartnerRef partner, boolean mayTopup, boolean owner) {
    var settings = repository.settings();
    var wallet = repository.wallet(partner.id());
    var name = partner.name();
    return new WalletView(partner.id(), name, partner.status(), !partner.active(), wallet.balance(), wallet.held(),
        settings.lowBalanceThreshold(), Rules.low(wallet.balance(), settings), settings.minTopup(), settings.maxTopup(),
        mayTopup && partner.active(), owner && partner.active(),
        repository.ledger(partner.id(), RECENT).stream().map(LedgerView::of).toList(),
        repository.requests(partner.id(), null, RECENT).stream().map(q -> RequestView.of(q, name)).toList());
  }

  private RequestView replay(UUID requestId, UUID partnerId, RequestKind kind) {
    var existing = repository.byClientRequest(requestId);
    if (existing.isEmpty()) return null;
    var q = existing.get();
    if (!q.partnerId().equals(partnerId) || q.kind() != kind) throw fail(Kind.CONFLICT, "REQUEST_ID_REUSED");
    return RequestView.of(q, partners.byId(partnerId).map(PartnerRef::name).orElse(""));
  }

  private WalletRequest newRequest(UUID requestId, UUID partner, RequestKind kind, long amount, PaymentMethod method,
      String reference, String note, UUID actor) {
    return new WalletRequest(UUID.randomUUID(), requestId, partner, kind, amount, method, blankToNull(reference),
        blankToNull(note), RequestStatus.PENDING, actor, clock.instant(), null, null, null, null, null);
  }

  private WalletRequest pending(UUID id) {
    var q = repository.lockRequest(id).orElseThrow(() -> fail(Kind.NOT_FOUND, "WALLET_REQUEST_NOT_FOUND"));
    if (q.status() != RequestStatus.PENDING) throw fail(Kind.CONFLICT, "WALLET_REQUEST_DECIDED");
    return q;
  }

  private PartnerRef partner(UUID id) {
    return partners.byId(id).orElseThrow(() -> fail(Kind.NOT_FOUND, "PARTNER_NOT_FOUND"));
  }

  private static void requireActive(PartnerRef partner) {
    if (!partner.active()) throw fail(Kind.CONFLICT, "PARTNER_NOT_ACTIVE");
  }

  private void requireLimits(long amount) {
    if (!Rules.withinLimits(amount, repository.settings())) throw fail(Kind.INVALID, "TOPUP_OUT_OF_LIMITS");
  }

  private static String blankToNull(String v) {
    return v == null || v.isBlank() ? null : v.strip();
  }

  static DomainException fail(Kind kind, String code) {
    return new DomainException(kind, code);
  }
}
