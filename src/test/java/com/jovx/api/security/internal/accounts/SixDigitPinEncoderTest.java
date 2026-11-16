package com.jovx.api.security.internal.accounts;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SixDigitPinEncoderTest {
    private final SixDigitPinEncoder encoder = new SixDigitPinEncoder();

    @Test void leadingZeroIsPreservedAndHashesAreSalted() {
        String first = encoder.encode("048291");
        assertNotEquals(first, encoder.encode("048291"));
        assertTrue(first.startsWith("{bcrypt}"));
        assertTrue(encoder.matches("048291", first));
        assertFalse(encoder.matches("048292", first));
        assertFalse(encoder.matches("48291", first));
        assertFalse(encoder.matches("048291", "048291"));
        assertFalse(encoder.matches("048291", "{noop}048291"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "1234567", " 123456", "123456 ", "12a456", "١٢٣٤٥٦"})
    void invalidPinsAreNotSilentlyTrimmedOrTruncated(String input) {
        assertThrows(IllegalArgumentException.class, () -> SixDigitPinEncoder.validateForCreation(input));
    }
}
