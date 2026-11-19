package com.jovx.api.security.api;

import java.util.UUID;

/**
 * Trusted module API for MEMBER accounts. The caller owns the use-case transaction and
 * enforces administrative authorization. A member account is created PENDING without a
 * usable PIN; it becomes ACTIVE only when the member sets a PIN through the card claim.
 */
public interface MemberAccounts {
    /** @throws PhoneAlreadyRegistered when any account already uses the phone. */
    UUID createUnclaimed(String name, String phone);

    /** True while the account is a MEMBER that has never set a PIN. */
    boolean unclaimed(UUID account);

    /** True when the normalized phone equals the account's login phone. */
    boolean phoneMatches(UUID account, String phone);

    /** Sets the first PIN and activates. Refuses an account that already has a PIN. */
    void claim(UUID account, String pin);

    /** Administrative reset: replaces the PIN, clears lockout and revokes every session. */
    void resetPin(UUID account, String pin);

    final class PhoneAlreadyRegistered extends RuntimeException {
        public PhoneAlreadyRegistered() { super("Phone already registered"); }
    }
}
