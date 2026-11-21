package com.jovx.api.sales.service;

import static com.jovx.api.sales.model.SalesModels.*;
import static com.jovx.api.sales.service.Sellers.*;

import com.jovx.api.catalog.api.ProductSales;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.points.api.PointsDebits;
import com.jovx.api.sales.dto.SalesDtos.*;
import com.jovx.api.sales.model.SalesModels.*;
import com.jovx.api.sales.repository.SalesRepository;
import com.jovx.api.security.api.ActorAccess;
import com.jovx.api.wallets.api.WalletCredits;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Invoice cancellation with the member's consent (user decision 2026-11-21, supersedes decision 7).
 *
 * <p>The partner, or the employee who made the sale (with a selling grant), asks within 24 hours; the
 * member accepts or rejects within 24 hours of the request. Acceptance is one transaction: the points
 * leave the member (refused if the balance is short), return to the partner wallet, the stock comes
 * back and the sale becomes CANCELLED. Locks are always taken sale first, then request.
 */
@Service
@Profile("local-postgres")
public class SaleCancellationService {
  static final String POINTS_KIND = "SALE_CANCELLED";
  private final SalesRepository repository;
  private final Sellers sellers;
  private final ActorAccess actors;
  private final PointsDebits points;
  private final WalletCredits wallets;
  private final ProductSales products;
  private final SaleViews views;
  private final SaleEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SaleCancellationService(
      SalesRepository repository, Sellers sellers, ActorAccess actors, PointsDebits points, WalletCredits wallets,
      ProductSales products, SaleViews views, SaleEvents events,
      @Qualifier("salesTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.sellers = sellers; this.actors = actors; this.points = points;
    this.wallets = wallets; this.products = products; this.views = views; this.events = events; this.tx = tx;
    this.clock = clock;
  }

  /** Seller side: open a request the member must answer. */
  public SaleView request(UUID actor, UUID sid, String role, UUID saleId, CancelRequest body) {
    return tx.execute(s -> {
      var seller = sellers.of(actor, sid, role);
      if (!seller.may(SELL) && !seller.may(FREE)) throw fail(Kind.FORBIDDEN, "FORBIDDEN");
      var sale = repository.lockSale(saleId).filter(x -> x.partnerId().equals(seller.partner().id()))
          .orElseThrow(() -> fail(Kind.NOT_FOUND, "SALE_NOT_FOUND"));
      if (!seller.owner() && !sale.sellerId().equals(actor)) throw fail(Kind.FORBIDDEN, "SALE_NOT_YOURS");
      if (sale.cancelled()) throw fail(Kind.CONFLICT, "SALE_ALREADY_CANCELLED");
      Instant now = clock.instant();
      if (!now.isBefore(sale.createdAt().plus(CANCEL_REQUEST_WINDOW))) throw fail(Kind.CONFLICT, "CANCEL_WINDOW_CLOSED");
      var pending = repository.pendingFor(saleId);
      if (pending.isPresent()) {
        if (pending.get().open(now)) throw fail(Kind.CONFLICT, "CANCEL_ALREADY_PENDING");
        repository.decide(pending.get().id(), CancellationStatus.EXPIRED, now);
      }
      String reason = body == null || body.reason() == null || body.reason().isBlank() ? null : body.reason().strip();
      var request = new Cancellation(UUID.randomUUID(), saleId, actor, now, now.plus(CANCEL_DECISION_WINDOW),
          CancellationStatus.PENDING, reason, null);
      repository.insertCancellation(request);
      events.cancellationRequested(sale, request);
      return views.sale(sale, repository.lines(saleId));
    });
  }

  /** Member side: sales waiting for this member's answer. */
  public List<SaleView> awaiting(UUID actor, UUID sid) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "MEMBER");
      var sales = repository.salesAwaitingMember(actor, clock.instant());
      return views.sales(sales, repository.lines(sales.stream().map(Sale::id).toList()));
    });
  }

  /**
   * Accept or reject. Repeating the same answer returns the sale unchanged (safe retry); answering a
   * request that was already decided otherwise, or that expired, is refused.
   */
  public SaleView decide(UUID actor, UUID sid, UUID cancellationId, boolean accept) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "MEMBER");
      var found = repository.cancellation(cancellationId)
          .orElseThrow(() -> fail(Kind.NOT_FOUND, "CANCEL_REQUEST_NOT_FOUND"));
      var sale = repository.lockSale(found.saleId()).filter(x -> x.memberAccount().equals(actor))
          .orElseThrow(() -> fail(Kind.NOT_FOUND, "CANCEL_REQUEST_NOT_FOUND"));
      var request = repository.lockCancellation(cancellationId).orElseThrow();
      var wanted = accept ? CancellationStatus.ACCEPTED : CancellationStatus.REJECTED;
      if (request.status() == wanted) return views.sale(sale, repository.lines(sale.id()));
      Instant now = clock.instant();
      if (request.status() != CancellationStatus.PENDING) throw fail(Kind.CONFLICT, "CANCEL_REQUEST_CLOSED");
      if (!request.open(now)) throw fail(Kind.CONFLICT, "CANCEL_REQUEST_EXPIRED");
      var lines = repository.lines(sale.id());
      if (accept) {
        if (!points.debit(actor, sale.points(), POINTS_KIND, sale.id(), now))
          throw fail(Kind.CONFLICT, "INSUFFICIENT_MEMBER_POINTS");
        wallets.returnSalePoints(sale.partnerId(), sale.points(), sale.id(), actor, now);
        for (var line : lines) products.returnStock(line.productId(), line.quantity(), now);
        repository.markCancelled(sale.id(), now);
      }
      repository.decide(request.id(), wanted, now);
      var after = repository.sale(sale.id()).orElseThrow();
      events.cancellationDecided(after, request, accept, now);
      return views.sale(after, lines);
    });
  }

  private static DomainException fail(Kind kind, String code) {
    return new DomainException(kind, code);
  }
}
