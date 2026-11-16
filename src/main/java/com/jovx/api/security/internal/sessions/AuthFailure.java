package com.jovx.api.security.internal.sessions;

public final class AuthFailure extends RuntimeException {
    private final int status;
    private final String code;
    private final long retrySeconds;
    public AuthFailure(int status, String code) { this(status, code, 0); }
    public AuthFailure(int status, String code, long retrySeconds) {
        super(code); this.status = status; this.code = code; this.retrySeconds = retrySeconds;
    }
    public int status() { return status; }
    public String code() { return code; }
    public long retrySeconds() { return retrySeconds; }
    public static AuthFailure invalid() { return new AuthFailure(401, "INVALID_CREDENTIALS"); }
}
