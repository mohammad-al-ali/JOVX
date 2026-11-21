package com.jovx.api.sales.service;

import static com.jovx.api.sales.service.Sellers.*;

import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.sales.dto.SalesDtos.*;
import com.jovx.api.sales.model.SalesModels.*;
import com.jovx.api.sales.repository.SalesRepository;
import com.jovx.api.security.api.ActorAccess;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Receipts, history and the dashboard (decisions 39-41). The owner sees every store; an employee sees
 * the sales of its stores with "sale.read.branch", otherwise only its own sales with "sale.read.own".
 */
@Service
@Profile("local-postgres")
public class SalesReadService {
  private static final Set<Integer> PERIODS = Set.of(1, 7, 30);
  private final SalesRepository repository;
  private final Sellers sellers;
  private final SaleViews views;
  private final ActorAccess actors;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SalesReadService(
      SalesRepository repository, Sellers sellers, SaleViews views, ActorAccess actors,
      @Qualifier("salesTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.sellers = sellers; this.views = views; this.actors = actors;
    this.tx = tx; this.clock = clock;
  }

  public List<SaleView> history(UUID actor, UUID sid, String role, UUID storeId, int offset, int limit) {
    return tx.execute(s -> {
      var scope = readScope(sellers.of(actor, sid, role), storeId);
      var sales = repository.sales(scope, null, offset, limit);
      return views.sales(sales, repository.lines(sales.stream().map(Sale::id).toList()));
    });
  }

  public SaleView receipt(UUID actor, UUID sid, String role, UUID saleId) {
    return tx.execute(s -> {
      var scope = readScope(sellers.of(actor, sid, role), null);
      var sale = repository.sale(saleId).filter(x -> visible(x, scope))
          .orElseThrow(() -> fail(Kind.NOT_FOUND, "SALE_NOT_FOUND"));
      return views.sale(sale, repository.lines(sale.id()));
    });
  }

  public SalesSummary summary(UUID actor, UUID sid, String role, UUID storeId, int days) {
    if (!PERIODS.contains(days)) throw fail(Kind.INVALID, "INVALID_PERIOD");
    return tx.execute(s -> {
      var seller = sellers.of(actor, sid, role);
      Scope scope;
      if (seller.may(REPORT_PARTNER)) scope = new Scope(seller.partner().id(), null, null);
      else if (seller.may(REPORT_STORES)) scope = new Scope(seller.partner().id(), seller.stores().keySet(), null);
      else throw fail(Kind.FORBIDDEN, "FORBIDDEN");
      scope = narrow(scope, storeId);
      var since = Rules.since(clock.instant(), days);
      var t = repository.totals(scope, since);
      var top = repository.topProducts(scope, since, 5).stream()
          .map(p -> new TopProductView(p.productId(), p.name(), p.quantity(), p.net())).toList();
      return new SalesSummary(days, since, t.sales(), t.customers(), t.gross(), t.discount(), t.net(), t.points(), top);
    });
  }

  /** The member's own receipts and lifetime savings (decision 42). */
  public MemberPurchases memberPurchases(UUID actor, UUID sid, int offset, int limit) {
    return tx.execute(s -> {
      actors.requireRole(actor, sid, "MEMBER");
      var sales = repository.memberSales(actor, offset, limit);
      long[] totals = repository.memberTotals(actor);
      return new MemberPurchases(totals[0], totals[1], totals[2],
          views.sales(sales, repository.lines(sales.stream().map(Sale::id).toList())));
    });
  }

  private static Scope readScope(Sellers.Seller seller, UUID storeId) {
    Scope scope;
    if (seller.owner()) scope = new Scope(seller.partner().id(), null, null);
    else if (seller.may(READ_STORES)) scope = new Scope(seller.partner().id(), seller.stores().keySet(), null);
    else if (seller.may(READ_OWN)) scope = new Scope(seller.partner().id(), null, seller.account());
    else throw fail(Kind.FORBIDDEN, "FORBIDDEN");
    return narrow(scope, storeId);
  }

  /** An optional store filter can only narrow what the reader may already see. */
  private static Scope narrow(Scope scope, UUID storeId) {
    if (storeId == null) return scope;
    if (scope.stores() != null && !scope.stores().contains(storeId)) throw fail(Kind.NOT_FOUND, "STORE_NOT_FOUND");
    return new Scope(scope.partnerId(), Set.of(storeId), scope.seller());
  }

  private static boolean visible(Sale sale, Scope scope) {
    return sale.partnerId().equals(scope.partnerId())
        && (scope.stores() == null || scope.stores().contains(sale.storeId()))
        && (scope.seller() == null || scope.seller().equals(sale.sellerId()));
  }

  private static DomainException fail(Kind kind, String code) {
    return new DomainException(kind, code);
  }
}
