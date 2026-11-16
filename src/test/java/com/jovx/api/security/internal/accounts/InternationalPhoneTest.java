package com.jovx.api.security.internal.accounts;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InternationalPhoneTest {
    @ParameterizedTest
    @ValueSource(strings = {"+12025550101", "+1 (202) 555-0101", "0012025550101", "+١٢٠٢٥٥٥٠١٠١", " +۱۲۰۲۵۵۵۰۱۰۱ ", "+1\u00a0202.555.0101"})
    void equivalentInternationalInputsResolveToOneKey(String input) {
        assertEquals("+12025550101", InternationalPhone.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"2025550101", "", "+99912345678", "+12025550101 ext 5", "+1-800-FLOWERS",
        "call +12025550101", "+12025550101;5", "++12025550101", "+00012345", "+12025550101111111111"})
    void ambiguousOrInvalidInputsAreRejectedWithoutGuessingCountry(String input) {
        assertThrows(IllegalArgumentException.class, () -> InternationalPhone.normalize(input));
    }

    @Test void nullIsRejectedAndCanonicalizationIsIdempotent() {
        assertThrows(IllegalArgumentException.class, () -> InternationalPhone.normalize(null));
        String canonical = InternationalPhone.normalize("+44 20 7946 0018");
        assertEquals(canonical, InternationalPhone.normalize(canonical));
    }
}
