package com.jovx.api.membership.dto;

import com.jovx.api.membership.model.MembershipModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class MembershipDtos {
  private MembershipDtos() {}

  // ---- plans ----

  public record PlanRequest(
      @NotBlank @Size(max = 100) String name,
      @NotNull @PositiveOrZero Long priceAmount,
      @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
      @NotNull @Min(1) @Max(120) Integer durationMonths,
      @NotNull @Min(0) @Max(20) Integer familySlots,
      @NotNull Boolean active) {}

  public record PlanView(
      UUID id, String name, long priceAmount, String currency, int durationMonths, int familySlots, boolean active) {
    public static PlanView of(Plan p) {
      return new PlanView(p.id(), p.name(), p.priceAmount(), p.currency(), p.durationMonths(), p.familySlots(), p.active());
    }
  }

  // ---- cards ----

  public record CardBatchRequest(
      @NotBlank @Size(max = 100) String batchLabel,
      @NotEmpty @Size(max = 500) List<@NotNull @Size(max = 200) String> codes) {}

  public record CardBatchResult(int added, List<String> alreadyRegistered, List<String> invalid) {}

  public record CardView(
      UUID id, String code, String batchLabel, CardStatus status, UUID memberId, Instant registeredAt,
      Instant assignedAt, Instant suspendedAt) {
    public static CardView of(Card c) {
      return new CardView(c.id(), c.code(), c.batch(), c.status(), c.member(), c.registeredAt(), c.assignedAt(), c.suspendedAt());
    }
  }

  public record CardStock(Map<CardStatus, Long> counts, List<CardView> cards) {}

  // ---- members (administration) ----

  public record PaymentRequest(@NotNull PaymentMethod method, @Size(max = 200) String reference) {}

  /** requestId is generated once per form by the client; a retry returns the first result. */
  public record SellMembershipRequest(
      @NotNull UUID requestId,
      @NotBlank @Size(max = 200) String name,
      @NotBlank @Size(max = 64) String phone,
      @NotBlank @Size(max = 200) String cardCode,
      @NotNull UUID planId,
      @NotNull @Valid PaymentRequest payment) {}

  public record RenewMembershipRequest(
      @NotNull UUID requestId, @NotNull UUID planId, @NotNull @Valid PaymentRequest payment) {}

  public record MembershipTermResult(UUID memberId, String memberNumber, Instant expiresAt, PaymentView payment) {}

  public record ResetPinRequest(@NotNull @Pattern(regexp = "[0-9]{6}") String pin) {
    @Override
    public String toString() {
      return "ResetPinRequest[redacted]";
    }
  }

  public record PaymentView(
      UUID id, PaymentKind kind, String planName, long amount, String currency, PaymentMethod method,
      String reference, Instant previousExpiresAt, Instant newExpiresAt, UUID recordedBy, String recordedByName,
      Instant createdAt) {}

  public record MemberSummary(
      UUID id, String memberNumber, String name, String phone, String planName, Instant expiresAt,
      MembershipState state, boolean claimed, CardStatus cardStatus) {}

  public record MemberDetail(
      UUID id, String memberNumber, String name, String phone, UUID planId, String planName,
      Instant startsAt, Instant expiresAt, MembershipState state, boolean claimed, CardView card,
      List<PaymentView> payments) {}

  // ---- member self-service ----

  public record ClaimRequest(
      @NotBlank @Size(max = 200) String cardCode,
      @NotBlank @Size(max = 64) String phone,
      @NotNull @Pattern(regexp = "[0-9]{6}") String pin) {
    @Override
    public String toString() {
      return "ClaimRequest[redacted]";
    }
  }

  public record MemberCardView(String maskedCode, CardStatus status) {}

  public record MemberProfile(
      UUID id, String memberNumber, String name, String planName, Instant startsAt, Instant expiresAt,
      MembershipState state, MemberCardView card, boolean canShowQr) {}

  public record ScanToken(String token, Instant expiresAt) {
    @Override
    public String toString() {
      return "ScanToken[redacted]";
    }
  }
}
