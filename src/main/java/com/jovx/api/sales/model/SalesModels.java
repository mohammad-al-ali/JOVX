package com.jovx.api.sales.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

public final class SalesModels {
  private SalesModels() {}

  /** Decision 16: after the scan the seller has 5 minutes to finish the invoice. */
  public static final Duration SCAN_WINDOW = Duration.ofMinutes(5);
  /** Decision 41: "today" and every report follow Damascus time; storage stays UTC. */
  public static final ZoneId DAMASCUS = ZoneId.of("Asia/Damascus");
  public static final long MAX_AMOUNT = 1_000_000_000_000L;
  public static final int MAX_LINES = 50;
  public static final int MAX_QUANTITY = 1000;

  /** Cancellation (decision 2026-11-21): asked within 24 h of the sale, decided by the member within 24 h. */
  public static final Duration CANCEL_REQUEST_WINDOW = Duration.ofHours(24);
  public static final Duration CANCEL_DECISION_WINDOW = Duration.ofHours(24);

  public enum SaleKind { PRODUCTS, FREE }

  public enum SaleStatus { COMPLETED, CANCELLED }

  public enum CancellationStatus { PENDING, ACCEPTED, REJECTED, EXPIRED }

  public record Cancellation(
      UUID id, UUID saleId, UUID requestedBy, Instant requestedAt, Instant expiresAt, CancellationStatus status,
      String reason, Instant decidedAt) {
    /** Pending and still within the member's decision window. */
    public boolean open(Instant now) {
      return status == CancellationStatus.PENDING && now.isBefore(expiresAt);
    }
  }

  public enum ScanMethod { QR, CARD }

  public record Scan(
      UUID id, UUID partnerId, UUID storeId, UUID memberAccount, UUID cardId, ScanMethod method,
      UUID scannedBy, Instant createdAt, Instant expiresAt, Instant consumedAt) {}

  public record Sale(
      UUID id, UUID requestId, String requestDigest, UUID partnerId, UUID storeId, UUID memberAccount, UUID cardId,
      UUID scanId, SaleKind kind, String currency, long grossAmount, long discountAmount, long netAmount,
      BigDecimal discountPercent, long points, UUID sellerId, Instant createdAt, SaleStatus status,
      Instant cancelledAt) {
    public boolean cancelled() {
      return status == SaleStatus.CANCELLED;
    }
  }

  public record SaleLine(
      int lineNo, UUID productId, String name, long unitPrice, long unitMemberPrice, long unitPoints, int quantity) {
    public long gross() { return unitPrice * quantity; }
    public long net() { return unitMemberPrice * quantity; }
    public long points() { return unitPoints * quantity; }
  }

  /** Which sales a reader may see: one partner, optionally limited to stores and/or one seller. */
  public record Scope(UUID partnerId, Set<UUID> stores, UUID seller) {}

  public record Totals(long sales, long customers, long gross, long discount, long net, long points) {}

  public record TopProduct(UUID productId, String name, long quantity, long net) {}

  /** The money and points arithmetic of decisions 6, 10, 12 and 14. */
  public static final class Rules {
    private Rules() {}

    static long halfUp(BigDecimal value) {
      return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** 2% of the total before discount, to the nearest point (12,345 → 247). */
    public static long minimumPoints(long gross) {
      return halfUp(BigDecimal.valueOf(gross).multiply(new BigDecimal("0.02")));
    }

    /** 100,000 at 10% → 10,000. */
    public static long discount(long gross, BigDecimal percent) {
      return halfUp(BigDecimal.valueOf(gross).multiply(percent).divide(BigDecimal.valueOf(100)));
    }

    /** The seller may raise the partner's minimum discount, never lower it, and never past 100%. */
    public static boolean validDiscount(BigDecimal percent, BigDecimal minimum) {
      return percent.scale() <= 2 && percent.compareTo(minimum) >= 0 && percent.compareTo(BigDecimal.valueOf(100)) <= 0;
    }

    /** Start of the Damascus day {@code days - 1} days before today, so days=1 means "today". */
    public static Instant since(Instant now, int days) {
      return now.atZone(DAMASCUS).toLocalDate().minusDays(days - 1L).atStartOfDay(DAMASCUS).toInstant();
    }
  }
}
