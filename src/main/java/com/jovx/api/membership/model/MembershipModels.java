package com.jovx.api.membership.model;

import java.time.*;
import java.util.UUID;
import java.util.regex.Pattern;

public final class MembershipModels {
  private MembershipModels() {}

  /** Calendar terms, "today" and reports follow Damascus time (plan decision 41). */
  public static final ZoneId DAMASCUS = ZoneId.of("Asia/Damascus");

  public static final Duration SCAN_TOKEN_LIFETIME = Duration.ofSeconds(60);

  public enum CardStatus { IN_STOCK, ASSIGNED, SUSPENDED }

  public enum PaymentMethod { CASH, TRANSFER, OTHER }

  public enum PaymentKind { SALE, RENEWAL }

  public enum MembershipState { ACTIVE, EXPIRED }

  public record Plan(
      UUID id, String name, long priceAmount, String currency, int durationMonths, int familySlots, boolean active) {}

  public record Member(UUID accountId, String memberNumber, UUID planId, Instant startsAt, Instant expiresAt) {}

  public record Card(
      UUID id, String code, String batch, CardStatus status, UUID member,
      Instant registeredAt, Instant assignedAt, Instant suspendedAt) {}

  public record Payment(
      UUID id, UUID requestId, UUID member, UUID planId, PaymentKind kind, long amount, String currency,
      PaymentMethod method, String reference, Instant previousExpiresAt, Instant newExpiresAt,
      UUID recordedBy, Instant createdAt) {}

  /** Pure membership-term rules. */
  public static final class Terms {
    private static final Pattern CARD_CODE = Pattern.compile("[A-Za-z0-9._-]{4,64}");

    private Terms() {}

    /** Calendar months in Damascus time: 31 Jan + 1 month = end of February. */
    public static Instant end(Instant start, int months) {
      if (months < 1) throw new IllegalArgumentException("months");
      return ZonedDateTime.ofInstant(start, DAMASCUS).plusMonths(months).toInstant();
    }

    /** Renewal adds to the remaining period, or starts now when already expired (decision 51). */
    public static Instant renewalStart(Instant now, Instant currentExpiry) {
      return currentExpiry.isAfter(now) ? currentExpiry : now;
    }

    public static MembershipState state(Instant now, Instant expiresAt) {
      return now.isBefore(expiresAt) ? MembershipState.ACTIVE : MembershipState.EXPIRED;
    }

    public static String memberNumber(long sequence) {
      if (sequence < 1) throw new IllegalArgumentException("sequence");
      // Locale.ROOT: an Arabic default locale would print Arabic-Indic digits.
      return String.format(java.util.Locale.ROOT, "JVX-%06d", sequence);
    }

    /** Codes come from the external printing system and are kept as printed, trimmed only. */
    public static String cardCode(String raw) {
      if (raw == null) return null;
      String code = raw.strip();
      return CARD_CODE.matcher(code).matches() ? code : null;
    }

    /** Only the last four characters leave the server for display. */
    public static String maskedCode(String code) {
      return code == null || code.length() <= 4 ? code : "•••• " + code.substring(code.length() - 4);
    }
  }
}
