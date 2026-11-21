package com.jovx.api.wallets.model;

import java.time.Instant;
import java.util.UUID;

public final class WalletModels {
  private WalletModels() {}

  public enum RequestKind { TOPUP, REFUND }

  public enum RequestStatus { PENDING, APPROVED, REJECTED }

  /** Decision 20: cash, transfer, Sham Cash, other — always with an optional reference. */
  public enum PaymentMethod { CASH, TRANSFER, SHAM_CASH, OTHER }

  public enum LedgerKind { TOPUP_APPROVED, DIRECT_TOPUP, REFUND_HELD, REFUND_RELEASED, SALE_POINTS, SALE_POINTS_RETURNED }

  public record Wallet(UUID partnerId, long balance, long held) {}

  public record Settings(long minTopup, long maxTopup, long lowBalanceThreshold) {}

  public record WalletRequest(
      UUID id, UUID requestId, UUID partnerId, RequestKind kind, long amount, PaymentMethod method,
      String reference, String note, RequestStatus status, UUID requestedBy, Instant createdAt,
      UUID decidedBy, Instant decidedAt, String decisionReason, PaymentMethod payoutMethod, String payoutReference) {}

  public record LedgerEntry(
      UUID id, long delta, long balanceAfter, LedgerKind kind, UUID walletRequestId, UUID actorId, Instant createdAt) {}

  /** Pure wallet rules. */
  public static final class Rules {
    private Rules() {}

    /** Decision 58: every top-up, requested or direct, stays within the configured limits. */
    public static boolean withinLimits(long amount, Settings s) {
      return amount >= s.minTopup() && amount <= s.maxTopup();
    }

    public static boolean validLimits(long min, long max, long threshold) {
      return min >= 1 && max >= min && threshold >= 0;
    }

    /** Decision 61: shown as a banner while the spendable balance is below the threshold. */
    public static boolean low(long balance, Settings s) {
      return balance < s.lowBalanceThreshold();
    }

    /** A refund can reserve at most the spendable balance. */
    public static boolean refundable(long amount, Wallet w) {
      return amount > 0 && amount <= w.balance();
    }
  }
}
