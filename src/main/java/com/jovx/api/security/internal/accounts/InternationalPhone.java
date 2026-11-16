package com.jovx.api.security.internal.accounts;

import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;

/** Canonical identity key; never guesses a country from the host or user locale. */
public final class InternationalPhone {
    private static final PhoneNumberUtil PHONES = PhoneNumberUtil.getInstance();

    private InternationalPhone() { }

    public static String normalize(String input) {
        if (input == null || input.isBlank() || input.length() > 64) throw invalid();
        var digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            int digit = Character.digit(c, 10);
            if (digit >= 0) digits.append(digit);
            else if (c == '+') digits.append(c);
            else if (!(Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '-' || c == '(' || c == ')' || c == '.')) throw invalid();
        }
        String value = digits.toString();
        if (value.startsWith("00")) value = "+" + value.substring(2);
        if (!value.matches("\\+[1-9][0-9]{1,14}")) throw invalid();
        try {
            var number = PHONES.parse(value, "ZZ");
            if (number.hasExtension() || !PHONES.isValidNumber(number)) throw invalid();
            return PHONES.format(number, PhoneNumberUtil.PhoneNumberFormat.E164);
        } catch (NumberParseException ex) {
            throw invalid();
        }
    }

    private static InvalidPhoneNumber invalid() {
        return new InvalidPhoneNumber();
    }

    /** Safe machine-readable distinction; never includes the supplied number. */
    public static final class InvalidPhoneNumber extends IllegalArgumentException {
        private InvalidPhoneNumber() {
            super("An international phone number valid for its numbering plan is required");
        }
    }
}
