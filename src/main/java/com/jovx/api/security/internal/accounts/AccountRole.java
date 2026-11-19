package com.jovx.api.security.internal.accounts;

/** Predefined roles. Role facts live here once instead of string sets spread across services. */
public enum AccountRole {
    SUPER_ADMIN(false), ADMIN(false), PARTNER(true), MEMBER(true), ADMIN_EMPLOYEE(true), PARTNER_EMPLOYEE(true);

    private final boolean domainProvisioned;

    AccountRole(boolean domainProvisioned) { this.domainProvisioned = domainProvisioned; }

    /** Created/transferred only by its owning business module, never by a bare role assignment. */
    public boolean domainProvisioned() { return domainProvisioned; }

    public static boolean domainProvisioned(String key) {
        for (var role : values()) if (role.name().equals(key)) return role.domainProvisioned;
        return false;
    }
}
