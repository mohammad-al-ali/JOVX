package com.jovx.api.security.internal.accounts;

import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** New-account PIN policy. Legacy credential import requires a separate reviewed conversion. */
public final class SixDigitPinEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate = new DelegatingPasswordEncoder("bcrypt",
        Map.of("bcrypt", new BCryptPasswordEncoder(12)));

    @Override public String encode(CharSequence pin) {
        // DaoAuthenticationProvider also hashes a non-PIN dummy secret for timing protection.
        // Enforce the creation policy at the persistence boundary, not in this hashing primitive.
        return delegate.encode(pin);
    }

    public static void validateForCreation(CharSequence pin) {
        if (!valid(pin)) throw new IllegalArgumentException("PIN must contain exactly six digits");
    }

    @Override public boolean matches(CharSequence pin, String hash) {
        if (!valid(pin) || hash == null || !hash.startsWith("{bcrypt}")) return false;
        return delegate.matches(pin, hash);
    }

    private static boolean valid(CharSequence pin) {
        return pin != null && pin.length() == 6 && pin.toString().matches("[0-9]{6}");
    }
}
