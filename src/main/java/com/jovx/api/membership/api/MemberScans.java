package com.jovx.api.membership.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Identifies the member a partner scanned (decisions 16, 17). The caller owns the transaction.
 * A QR token works once and only for 60 seconds; a physical card works without a scan limit.
 */
public interface MemberScans {
  /** Prefix of the member app's QR text; anything else is read as a printed card code. */
  String QR_PREFIX = "JX2.";

  /** Uses up the QR token. Empty when unknown, already used or expired. */
  Optional<ScannedMember> consumeQr(String token, Instant now);

  /** The member a printed card is assigned to. Empty when the card is unknown or unsold. */
  Optional<ScannedMember> byCard(String cardCode);

  /** The member that a card belongs to, for receipts. */
  Optional<ScannedMember> byCardId(UUID cardId);

  /**
   * termActive: membership not ended. cardActive: the card is assigned, not suspended.
   * A sale needs both (decisions 50, 55).
   */
  record ScannedMember(
      UUID memberAccount, UUID cardId, String memberNumber, String maskedCard, boolean termActive, boolean cardActive) {
    public boolean canTransact() {
      return termActive && cardActive;
    }
  }
}
