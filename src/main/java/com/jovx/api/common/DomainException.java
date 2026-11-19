package com.jovx.api.common;

/**
 * A business-rule failure raised by any module's service layer.
 *
 * <p>Services describe WHAT went wrong ({@link Kind} plus a stable machine code such as
 * {@code PARTNER_NOT_FOUND}); the web layer alone decides the HTTP status. This keeps business
 * code free of Spring Web types (Dependency Inversion) and reusable from non-HTTP callers.
 */
public final class DomainException extends RuntimeException {
  /** The category of failure; the web adapter maps each kind to one HTTP status. */
  public enum Kind {
    /** Input breaks a business rule or format (HTTP 400). */
    INVALID,
    /** The actor may not perform this action (HTTP 403). */
    FORBIDDEN,
    /** The target does not exist or is outside the actor's scope (HTTP 404). */
    NOT_FOUND,
    /** The current state forbids the change (HTTP 409). */
    CONFLICT,
    /** A required external source answered badly (HTTP 502). */
    UPSTREAM_FAILED,
    /** A required dependency is not configured or not reachable (HTTP 503). */
    UNAVAILABLE
  }

  private final Kind kind;
  private final String code;

  public DomainException(Kind kind, String code) {
    super(code);
    this.kind = java.util.Objects.requireNonNull(kind);
    this.code = java.util.Objects.requireNonNull(code);
  }

  public Kind kind() {
    return kind;
  }

  public String code() {
    return code;
  }
}
