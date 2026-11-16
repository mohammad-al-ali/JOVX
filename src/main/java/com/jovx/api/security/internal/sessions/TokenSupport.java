package com.jovx.api.security.internal.sessions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

final class TokenSupport {
    private static final SecureRandom RANDOM = new SecureRandom();
    private TokenSupport() { }
    static String freshRefresh() {
        byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes);
        return "r1." + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    static boolean validRefresh(String token) { return token != null && token.matches("r1\\.[A-Za-z0-9_-]{43}"); }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
