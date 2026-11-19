package com.jovx.api.wallets.dto;

import com.jovx.api.wallets.model.WalletModels.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class WalletDtos {
  private WalletDtos() {}

  /** requestId is generated once per form; a retried submit returns the first request. */
  public record TopupRequest(
      @NotNull UUID requestId,
      @NotNull @Positive Long amount,
      @NotNull PaymentMethod method,
      @Size(max = 200) String reference,
      @Size(max = 500) String note) {}

  /** method/reference: how the partner wants the money returned. */
  public record RefundRequest(
      @NotNull UUID requestId,
      @NotNull @Positive Long amount,
      @NotNull PaymentMethod method,
      @Size(max = 200) String reference,
      @Size(max = 500) String note) {}

  /** For a refund the administration records how the money was paid out. */
  public record ApproveRequest(PaymentMethod payoutMethod, @Size(max = 200) String payoutReference) {}

  public record RejectRequest(@NotBlank @Size(max = 500) String reason) {}

  public record SettingsRequest(
      @NotNull @Positive Long minTopup, @NotNull @Positive Long maxTopup, @NotNull @PositiveOrZero Long lowBalanceThreshold) {}

  public record RequestView(
      UUID id, UUID partnerId, String partnerName, RequestKind kind, long amount, PaymentMethod method,
      String reference, String note, RequestStatus status, Instant createdAt, Instant decidedAt,
      String decisionReason, PaymentMethod payoutMethod, String payoutReference) {
    public static RequestView of(WalletRequest q, String partnerName) {
      return new RequestView(q.id(), q.partnerId(), partnerName, q.kind(), q.amount(), q.method(), q.reference(),
          q.note(), q.status(), q.createdAt(), q.decidedAt(), q.decisionReason(), q.payoutMethod(), q.payoutReference());
    }
  }

  public record LedgerView(long delta, long balanceAfter, LedgerKind kind, Instant createdAt) {
    public static LedgerView of(LedgerEntry e) {
      return new LedgerView(e.delta(), e.balanceAfter(), e.kind(), e.createdAt());
    }
  }

  /** frozen: the partner is not ACTIVE, so no top-up and (later) no sales (decisions 22, 36). */
  public record WalletView(
      UUID partnerId, String partnerName, String partnerStatus, boolean frozen, long balance, long held,
      long lowBalanceThreshold, boolean low, long minTopup, long maxTopup, boolean canRequestTopup,
      boolean canRequestRefund, List<LedgerView> ledger, List<RequestView> requests) {}

  public record WalletSummary(
      UUID partnerId, String partnerName, String partnerStatus, long balance, long held, boolean low, int pendingRequests) {}

  public record SettingsView(long minTopup, long maxTopup, long lowBalanceThreshold) {
    public static SettingsView of(Settings s) {
      return new SettingsView(s.minTopup(), s.maxTopup(), s.lowBalanceThreshold());
    }
  }
}
