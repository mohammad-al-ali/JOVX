package com.jovx.api.sales.service;

import static com.jovx.api.sales.model.SalesModels.*;
import static com.jovx.api.sales.service.Sellers.*;

import com.jovx.api.catalog.api.ProductSales;
import com.jovx.api.catalog.api.ProductSales.SellableProduct;
import com.jovx.api.common.DomainException;
import com.jovx.api.common.DomainException.Kind;
import com.jovx.api.membership.api.MemberScans;
import com.jovx.api.membership.api.MemberScans.ScannedMember;
import com.jovx.api.points.api.PointsCredits;
import com.jovx.api.sales.dto.SalesDtos.*;
import com.jovx.api.sales.model.SalesModels.*;
import com.jovx.api.sales.repository.SalesRepository;
import com.jovx.api.wallets.api.WalletDebits;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scan and sell. A sale is ONE transaction: the wallet debit, the member's points, the stock and the
 * receipt are saved together or not at all (decisions 6-17, 70-73). It is reversed only by a cancellation
 * the member accepts (SaleCancellationService).
 */
@Service
@Profile("local-postgres")
public class SaleService {
  static final String POINTS_KIND = "SALE";
  private final SalesRepository repository;
  private final Sellers sellers;
  private final MemberScans scans;
  private final ProductSales products;
  private final WalletDebits wallets;
  private final PointsCredits points;
  private final SaleViews views;
  private final SaleEvents events;
  private final TransactionTemplate tx;
  private final Clock clock;

  public SaleService(
      SalesRepository repository, Sellers sellers, MemberScans scans, ProductSales products, WalletDebits wallets,
      PointsCredits points, SaleViews views, SaleEvents events,
      @Qualifier("salesTransactions") TransactionTemplate tx, Clock clock) {
    this.repository = repository; this.sellers = sellers; this.scans = scans; this.products = products;
    this.wallets = wallets; this.points = points; this.views = views; this.events = events; this.tx = tx;
    this.clock = clock;
  }

  public SalesContext context(UUID actor, UUID sid, String role) {
    return tx.execute(s -> {
      var seller = sellers.of(actor, sid, role);
      var stores = seller.stores().values().stream().map(st -> new StoreOption(st.id(), st.name(), st.active())).toList();
      var can = new Abilities(seller.may(SCAN), seller.may(SELL), seller.may(FREE), seller.may(READ_OWN),
          seller.may(READ_STORES), seller.may(REPORT_STORES) || seller.may(REPORT_PARTNER));
      return new SalesContext(seller.partner().name(), seller.partner().active(),
          seller.partner().minimumDiscountPercent(), seller.owner(), stores, can);
    });
  }

  /** The invoice picker: approved, shown products of one store, at the member price. */
  public List<SellableProductView> sellable(UUID actor, UUID sid, String role, UUID storeId) {
    return tx.execute(s -> {
      var seller = sellers.of(actor, sid, role);
      seller.require(SELL);
      seller.sellingStore(storeId);
      return products.sellableIn(storeId).stream().map(p -> new SellableProductView(p.id(), p.name(), p.priceAmount(),
          p.memberPrice(), p.currency(), p.points(), p.stock(), p.outOfStock(), p.coverImageUrl())).toList();
    });
  }

  /** Decisions 16, 17: a used/expired QR or an unknown card is refused with one answer. */
  public ScanView scan(UUID actor, UUID sid, String role, ScanRequest r) {
    return tx.execute(s -> {
      var seller = sellers.of(actor, sid, role);
      seller.require(SCAN);
      var store = seller.sellingStore(r.storeId());
      Instant now = clock.instant();
      String code = r.code().strip();
      boolean qr = code.startsWith(MemberScans.QR_PREFIX);
      ScannedMember member = (qr ? scans.consumeQr(code, now) : scans.byCard(code))
          .orElseThrow(() -> fail(Kind.INVALID, "MEMBER_SCAN_INVALID"));
      requireCanBuy(member);
      var scan = new Scan(UUID.randomUUID(), seller.partner().id(), store.id(), member.memberAccount(), member.cardId(),
          qr ? ScanMethod.QR : ScanMethod.CARD, actor, now, now.plus(SCAN_WINDOW), null);
      repository.insertScan(scan);
      return new ScanView(scan.id(), store.id(), scan.expiresAt(), views.member(member),
          seller.partner().minimumDiscountPercent());
    });
  }

  public SaleView sell(UUID actor, UUID sid, String role, SaleRequest r) {
    return tx.execute(s -> {
      var seller = sellers.of(actor, sid, role);
      seller.require(r.kind() == SaleKind.FREE ? FREE : SELL);
      String digest = digest(r);
      repository.lockRequest(r.requestId());
      var earlier = repository.byRequest(r.requestId());
      if (earlier.isPresent()) {
        var sale = earlier.get();
        if (!sale.partnerId().equals(seller.partner().id()) || !sale.requestDigest().equals(digest))
          throw fail(Kind.CONFLICT, "REQUEST_ID_REUSED");
        return views.sale(sale, repository.lines(sale.id()));
      }
      Instant now = clock.instant();
      var scan = repository.lockScan(r.scanId())
          .filter(x -> x.partnerId().equals(seller.partner().id()) && x.scannedBy().equals(actor))
          .orElseThrow(() -> fail(Kind.NOT_FOUND, "SCAN_NOT_FOUND"));
      if (scan.consumedAt() != null) throw fail(Kind.CONFLICT, "SCAN_ALREADY_USED");
      if (!now.isBefore(scan.expiresAt())) throw fail(Kind.CONFLICT, "SCAN_EXPIRED");
      seller.sellingStore(scan.storeId());
      requireCanBuy(scans.byCardId(scan.cardId()).orElseThrow(() -> fail(Kind.CONFLICT, "CARD_NOT_ACTIVE")));

      UUID saleId = UUID.randomUUID();
      var lines = new ArrayList<SaleLine>();
      long gross, net, total;
      BigDecimal percent = null;
      if (r.kind() == SaleKind.PRODUCTS) {
        lines.addAll(productLines(r.lines(), scan.storeId()));
        gross = lines.stream().mapToLong(SaleLine::gross).sum();
        net = lines.stream().mapToLong(SaleLine::net).sum();
        total = lines.stream().mapToLong(SaleLine::points).sum();
      } else {
        var minimum = seller.partner().minimumDiscountPercent();
        gross = r.grossAmount() == null ? 0 : r.grossAmount();
        if (gross <= 0 || gross > MAX_AMOUNT) throw fail(Kind.INVALID, "INVALID_AMOUNT");
        percent = r.discountPercent() == null ? minimum : r.discountPercent().stripTrailingZeros();
        if (percent.scale() < 0) percent = percent.setScale(0);
        if (!Rules.validDiscount(percent, minimum)) throw fail(Kind.INVALID, "DISCOUNT_BELOW_MINIMUM");
        net = gross - Rules.discount(gross, percent);
        long minimumPoints = Rules.minimumPoints(gross);
        total = r.points() == null ? minimumPoints : r.points();
        if (total < minimumPoints) throw fail(Kind.INVALID, "POINTS_BELOW_MINIMUM");
      }
      if (!wallets.debitSalePoints(seller.partner().id(), total, saleId, actor, now))
        throw fail(Kind.CONFLICT, "INSUFFICIENT_WALLET_BALANCE");
      for (var l : lines) products.takeStock(l.productId(), l.quantity(), now);
      points.credit(scan.memberAccount(), total, POINTS_KIND, saleId, now);
      var sale = new Sale(saleId, r.requestId(), digest, seller.partner().id(), scan.storeId(), scan.memberAccount(),
          scan.cardId(), scan.id(), r.kind(), "SYP", gross, gross - net, net, percent, total, actor, now,
          SaleStatus.COMPLETED, null);
      repository.insertSale(sale, lines);
      repository.consumeScan(scan.id(), now);
      events.saleCompleted(sale);
      return views.sale(sale, lines);
    });
  }

  /** Locks products in a fixed order (no deadlock between two invoices) and prices them now. */
  private List<SaleLine> productLines(List<LineRequest> requested, UUID storeId) {
    if (requested == null || requested.isEmpty() || requested.size() > MAX_LINES) throw fail(Kind.INVALID, "INVALID_SALE_LINES");
    var quantities = new LinkedHashMap<UUID, Integer>();
    for (var l : requested)
      if (quantities.putIfAbsent(l.productId(), l.quantity()) != null) throw fail(Kind.INVALID, "INVALID_SALE_LINES");
    var locked = new HashMap<UUID, SellableProduct>();
    for (var id : new TreeSet<>(quantities.keySet())) {
      var p = products.lockForSale(id).filter(x -> x.storeId().equals(storeId))
          .orElseThrow(() -> fail(Kind.NOT_FOUND, "PRODUCT_NOT_FOUND"));
      if (!p.sellable()) throw fail(Kind.CONFLICT, "PRODUCT_NOT_AVAILABLE");
      if (!"SYP".equals(p.currency())) throw fail(Kind.CONFLICT, "PRODUCT_NOT_AVAILABLE");
      if (p.stock() != null && p.stock() < quantities.get(id)) throw fail(Kind.CONFLICT, "OUT_OF_STOCK");
      locked.put(id, p);
    }
    var lines = new ArrayList<SaleLine>();
    int no = 1;
    for (var e : quantities.entrySet()) {
      var p = locked.get(e.getKey());
      lines.add(new SaleLine(no++, p.id(), p.name(), p.priceAmount(), p.memberPrice(), p.points(), e.getValue()));
    }
    if (lines.stream().mapToLong(SaleLine::gross).sum() > MAX_AMOUNT) throw fail(Kind.INVALID, "INVALID_AMOUNT");
    return lines;
  }

  /** Decisions 50, 55: an ended membership or a stopped card cannot buy. */
  private static void requireCanBuy(ScannedMember m) {
    if (!m.termActive()) throw fail(Kind.CONFLICT, "MEMBERSHIP_EXPIRED");
    if (!m.cardActive()) throw fail(Kind.CONFLICT, "CARD_NOT_ACTIVE");
  }

  /** Same requestId with different content is refused instead of returning another sale. */
  static String digest(SaleRequest r) {
    var text = new StringBuilder(r.kind().name()).append('|').append(r.scanId());
    if (r.kind() == SaleKind.PRODUCTS && r.lines() != null)
      r.lines().stream().sorted(Comparator.comparing(LineRequest::productId))
          .forEach(l -> text.append('|').append(l.productId()).append(':').append(l.quantity()));
    else
      text.append('|').append(r.grossAmount()).append('|')
          .append(r.discountPercent() == null ? "" : r.discountPercent().stripTrailingZeros().toPlainString())
          .append('|').append(r.points());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static DomainException fail(Kind kind, String code) {
    return new DomainException(kind, code);
  }
}
