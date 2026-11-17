package com.jovx.api.security.internal.sessions;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.jovx.api.security.api.LoginEligibility;
import com.jovx.api.security.internal.accounts.InternationalPhone;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.transaction.support.TransactionTemplate;

public final class AuthSessionService implements com.jovx.api.security.api.AdministrationAccess, com.jovx.api.security.api.ActorAccess {
    private static final Set<String> ROLES = Set.of("PARTNER", "PARTNER_EMPLOYEE", "ADMIN", "SUPER_ADMIN");
    private final com.jovx.api.security.internal.roles.RoleStore roles;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuthenticationManager authentication;
    private final JwtEncoder signer;
    private final String issuer;
    private final String audience;
    private final Clock clock;
    private final AuthSettings settings;
    private final List<LoginEligibility> eligibility;
    private final PasswordEncoder pins;

    public AuthSessionService(DataSource source, AuthenticationManager authentication, JwtEncoder signer,
        String issuer, String audience, Clock clock, AuthSettings settings, List<LoginEligibility> eligibility, PasswordEncoder pins) {
        jdbc = new JdbcTemplate(source); roles = new com.jovx.api.security.internal.roles.RoleStore(source); tx = new TransactionTemplate(new JdbcTransactionManager(source));
        this.authentication = authentication; this.signer = signer; this.issuer = issuer; this.audience = audience;
        this.clock = clock; this.settings = settings; this.eligibility = List.copyOf(eligibility); this.pins = pins;
    }

    public LoginResult login(String inputPhone, String pin) {
        final String phone;
        try { phone = InternationalPhone.normalize(inputPhone); }
        catch (IllegalArgumentException ex) { throw AuthFailure.invalid(); }
        Outcome outcome = tx.execute(status -> {
            Account account = byPhone(phone);
            if (account == null) {
                try { authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(phone, pin)); }
                catch (org.springframework.security.authentication.AuthenticationServiceException ex) { throw ex; }
                catch (AuthenticationException ignored) { /* Keep Spring's unknown-user timing protection. */ }
                return Outcome.fail(AuthFailure.invalid());
            }
            if (!ROLES.contains(account.role()) || !"ACTIVE".equals(account.status())) return Outcome.fail(AuthFailure.invalid());
            Instant now = clock.instant();
            if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) {
                return Outcome.fail(locked(now, account.lockedUntil()));
            }
            try { authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(phone, pin)); }
            catch (org.springframework.security.authentication.AuthenticationServiceException ex) { throw ex; }
            catch (AuthenticationException ex) {
                int attempts = (account.lockedUntil() == null ? account.failed() : 0) + 1;
                Instant lockedUntil = attempts >= settings.maxPinAttempts() ? now.plus(settings.pinLockDuration()) : null;
                jdbc.update("UPDATE users SET failed_pin_attempts=?,locked_until=? WHERE id=?",
                    attempts, timestamp(lockedUntil), account.id().toString());
                audit(account.id(), lockedUntil == null ? "LOGIN_FAILURE" : "LOGIN_LOCKED", null);
                return Outcome.fail(lockedUntil == null ? AuthFailure.invalid() : locked(now, lockedUntil));
            }
            if (!eligible(account)) return Outcome.fail(AuthFailure.invalid());
            jdbc.update("UPDATE users SET failed_pin_attempts=0,locked_until=NULL WHERE id=?", account.id().toString());
            jdbc.update("UPDATE identity_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", timestamp(now), account.id().toString());
            UUID sessionId = UUID.randomUUID();
            Instant expires = now.plus(settings.refreshLifetime());
            jdbc.update("INSERT INTO identity_sessions (id,account_id,credential_tag,created_at,expires_at) VALUES (?,?,?,?,?)",
                sessionId.toString(), account.id().toString(), TokenSupport.digest(account.hash()), timestamp(now), timestamp(expires));
            LoginResult result = issue(account, sessionId, expires, now);
            audit(account.id(), "LOGIN_SUCCESS", sessionId);
            return Outcome.ok(result);
        });
        return outcome.unwrap(); // Failure counters/replay revocations must commit before throwing.
    }

    public LoginResult refresh(String token) {
        if (!TokenSupport.validRefresh(token)) throw AuthFailure.invalid();
        String digest = TokenSupport.digest(token);
        UUID accountId = locateRefreshAccount(digest);
        if (accountId == null) throw AuthFailure.invalid();
        Outcome outcome = tx.execute(status -> {
            Account account = byId(accountId);
            var tokens = jdbc.query("SELECT r.session_id,r.expires_at,r.consumed_at FROM identity_refresh_tokens r WHERE r.token_digest=? FOR UPDATE",
                (row, index) -> new RefreshRow(UUID.fromString(row.getString(1)), instant(row,2), instant(row,3)), digest);
            if (tokens.isEmpty()) return Outcome.fail(AuthFailure.invalid());
            RefreshRow refresh = tokens.getFirst();
            Session session = session(refresh.sessionId(), true);
            Instant now = clock.instant();
            if (!usable(account, session, now) || !refresh.expires().isAfter(now) || !eligible(account)) {
                revoke(refresh.sessionId(), now); return Outcome.fail(AuthFailure.invalid());
            }
            if (refresh.consumed() != null) {
                revoke(refresh.sessionId(), now);
                audit(accountId, "REFRESH_REPLAY", refresh.sessionId());
                return Outcome.fail(AuthFailure.invalid());
            }
            jdbc.update("UPDATE identity_refresh_tokens SET consumed_at=? WHERE token_digest=?", timestamp(now), digest);
            return Outcome.ok(issue(account, session.id(), session.expires(), now));
        });
        return outcome.unwrap();
    }

    public void logout(String token) {
        if (!TokenSupport.validRefresh(token)) return;
        String digest = TokenSupport.digest(token);
        UUID accountId = locateRefreshAccount(digest);
        if (accountId == null) return;
        tx.executeWithoutResult(status -> {
            byId(accountId); // Same account lock order as login/refresh.
            var ids = jdbc.query("SELECT session_id FROM identity_refresh_tokens WHERE token_digest=? FOR UPDATE",
                (row, index) -> UUID.fromString(row.getString(1)), digest);
            if (!ids.isEmpty()) { revoke(ids.getFirst(), clock.instant()); audit(accountId, "LOGOUT", ids.getFirst()); }
        });
    }

    public List<String> authorities(Jwt jwt) {
        final UUID id;
        final UUID sid;
        try { id = UUID.fromString(jwt.getSubject()); sid = UUID.fromString(jwt.getClaimAsString("sid")); }
        catch (RuntimeException ex) { throw AuthFailure.invalid(); }
        // A fresh DB read on every protected request; login lockout does not invalidate a valid session.
        Account account = account("a.id=?", id.toString(), false);
        Session session = session(sid, false);
        if (!usable(account, session, clock.instant()) || !id.equals(session.accountId())
            || !account.role().equals(jwt.getClaimAsString("role")) || !eligible(account)) throw AuthFailure.invalid();
        var result = new ArrayList<String>(); result.add("ROLE_" + account.role());
        result.addAll(roles.effectivePermissions(id));
        return List.copyOf(result);
    }

    public void requireRole(UUID accountId, UUID sessionId, String role) {
        Account account = byId(accountId);
        if (!usable(account, session(sessionId, false), clock.instant()) || !eligible(account)) throw AuthFailure.invalid();
        if (!role.equals(account.role())) throw new AuthFailure(403,"FORBIDDEN");
    }

    public void requirePermission(UUID accountId, UUID sessionId, String permission) {
        Account account = byId(accountId);
        if (!usable(account, session(sessionId, false), clock.instant()) || !eligible(account)) throw AuthFailure.invalid();
        if (!roles.effectivePermissions(accountId).contains(permission)) throw new AuthFailure(403,"FORBIDDEN");
    }

    @Override public void require(UUID accountId, UUID sessionId, String permission) {
        Account account = byId(accountId);
        if (!usable(account, session(sessionId, false), clock.instant()) || !eligible(account)) throw AuthFailure.invalid();
        if ("SUPER_ADMIN".equals(account.role())) return;
        if (!"ADMIN".equals(account.role()) || !roles.effectivePermissions(accountId).contains(permission))
            throw new AuthFailure(403,"FORBIDDEN");
    }

    @Override public void requireAny(UUID accountId, UUID sessionId, Set<String> permissions) {
        Account account = byId(accountId);
        if (!usable(account, session(sessionId, false), clock.instant()) || !eligible(account)) throw AuthFailure.invalid();
        if ("SUPER_ADMIN".equals(account.role())) return;
        if (!"ADMIN".equals(account.role()) || roles.effectivePermissions(accountId).stream().noneMatch(permissions::contains))
            throw new AuthFailure(403,"FORBIDDEN");
    }

    /** Approved for PARTNER only: verify the current PIN, store the new one and end every session. */
    public void changeOwnPin(UUID accountId, UUID sessionId, String currentPin, String newPin) {
        if (newPin == null || !newPin.matches("[0-9]{6}")) throw new AuthFailure(400, "INVALID_PIN");
        Outcome outcome = tx.execute(status -> {
            Account account = byId(accountId);
            Instant now = clock.instant();
            if (!usable(account, session(sessionId, false), now) || !eligible(account)) return Outcome.fail(AuthFailure.invalid());
            if (!"PARTNER".equals(account.role())) return Outcome.fail(new AuthFailure(403, "FORBIDDEN"));
            if (account.lockedUntil() != null && account.lockedUntil().isAfter(now)) return Outcome.fail(locked(now, account.lockedUntil()));
            if (!pins.matches(currentPin, account.hash())) {
                // Same counter as login so the change form cannot be used to guess PINs; the session stays valid.
                int attempts = (account.lockedUntil() == null ? account.failed() : 0) + 1;
                Instant lockedUntil = attempts >= settings.maxPinAttempts() ? now.plus(settings.pinLockDuration()) : null;
                jdbc.update("UPDATE users SET failed_pin_attempts=?,locked_until=? WHERE id=?", attempts, timestamp(lockedUntil), account.id().toString());
                audit(account.id(), "PIN_CHANGE_FAILURE", sessionId);
                return Outcome.fail(lockedUntil == null ? new AuthFailure(400, "CURRENT_PIN_INCORRECT") : locked(now, lockedUntil));
            }
            if (pins.matches(newPin, account.hash())) return Outcome.fail(new AuthFailure(400, "PIN_UNCHANGED"));
            jdbc.update("UPDATE users SET pin_hash=?,pin_changed_at=?,failed_pin_attempts=0,locked_until=NULL WHERE id=?",
                pins.encode(newPin), timestamp(now), account.id().toString());
            jdbc.update("UPDATE identity_sessions SET revoked_at=? WHERE account_id=? AND revoked_at IS NULL", timestamp(now), account.id().toString());
            audit(account.id(), "PIN_CHANGED", sessionId);
            return Outcome.ok(null);
        });
        outcome.unwrap(); // Failure counters must commit before throwing.
    }

    private LoginResult issue(Account account, UUID sid, Instant absoluteExpiry, Instant now) {
        Instant accessExpiry = now.plus(settings.accessLifetime());
        if (accessExpiry.isAfter(absoluteExpiry)) accessExpiry = absoluteExpiry;
        String refresh = TokenSupport.freshRefresh();
        jdbc.update("INSERT INTO identity_refresh_tokens (token_digest,session_id,created_at,expires_at) VALUES (?,?,?,?)",
            TokenSupport.digest(refresh), sid.toString(), timestamp(now), timestamp(absoluteExpiry));
        var claims = JwtClaimsSet.builder().issuer(issuer).audience(List.of(audience)).subject(account.id().toString())
            .issuedAt(now).notBefore(now).expiresAt(accessExpiry).id(UUID.randomUUID().toString())
            .claim("sid", sid.toString()).claim("role", account.role()).claim("token_use", "access").build();
        String access = signer.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims)).getTokenValue();
        return new LoginResult(true, new AccountView(account.id(), account.role()),
            new TokenPair(access, refresh, Duration.between(now, accessExpiry).toSeconds(), accessExpiry.getEpochSecond(), "bearer", absoluteExpiry));
    }

    private boolean eligible(Account account) {
        if (account == null) return false;
        if ("ADMIN".equals(account.role()) || "SUPER_ADMIN".equals(account.role())) return true;
        var policies = eligibility.stream().filter(p -> p.supports(account.role())).toList();
        return !policies.isEmpty() && policies.stream().allMatch(p -> p.allowed(account.id(), account.role()));
    }
    private boolean usable(Account account, Session session, Instant now) {
        return account != null && session != null && ROLES.contains(account.role()) && "ACTIVE".equals(account.status())
            && session.accountId().equals(account.id()) && session.revoked() == null && session.expires().isAfter(now)
            && session.credentialTag().equals(TokenSupport.digest(account.hash()));
    }
    private UUID locateRefreshAccount(String digest) {
        var ids = jdbc.query("SELECT s.account_id FROM identity_refresh_tokens r JOIN identity_sessions s ON s.id=r.session_id WHERE r.token_digest=?",
            (row,index) -> UUID.fromString(row.getString(1)), digest);
        return ids.isEmpty() ? null : ids.getFirst();
    }
    private void revoke(UUID sid, Instant now) { jdbc.update("UPDATE identity_sessions SET revoked_at=? WHERE id=? AND revoked_at IS NULL", timestamp(now), sid.toString()); }
    private void audit(UUID id, String action, UUID sid) {
        jdbc.update("INSERT INTO identity_auth_events (id,account_id,action,session_id,created_at) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(), id == null ? null : id.toString(), action, sid == null ? null : sid.toString(), timestamp(clock.instant()));
    }
    private Account byPhone(String phone) { return account("a.phone_e164=?", phone, true); }
    private Account byId(UUID id) { return account("a.id=?", id.toString(), true); }
    private Account account(String predicate, String value, boolean lock) {
        var rows = jdbc.query("SELECT a.id,a.phone_e164,r.\"key\",a.status,a.failed_pin_attempts,a.locked_until,a.pin_hash FROM users a JOIN roles r ON r.id=a.role_id WHERE " + predicate + (lock ? " FOR UPDATE" : ""),
            (row,index) -> new Account(UUID.fromString(row.getString(1)),row.getString(2),row.getString(3),row.getString(4),row.getInt(5),instant(row,6),row.getString(7)),value);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private Session session(UUID id, boolean lock) {
        var rows = jdbc.query("SELECT id,account_id,credential_tag,expires_at,revoked_at FROM identity_sessions WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (row,index) -> new Session(UUID.fromString(row.getString(1)),UUID.fromString(row.getString(2)),row.getString(3),instant(row,4),instant(row,5)),id.toString());
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private static AuthFailure locked(Instant now, Instant until) { return new AuthFailure(423,"ACCOUNT_LOCKED",Math.max(1,Duration.between(now,until).toSeconds()+1)); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet row, int index) throws SQLException { var value=row.getTimestamp(index); return value == null ? null : value.toInstant(); }
    private record Account(UUID id,String phone,String role,String status,int failed,Instant lockedUntil,String hash) { @Override public String toString(){return "Account[redacted]";} }
    private record Session(UUID id,UUID accountId,String credentialTag,Instant expires,Instant revoked) { }
    private record RefreshRow(UUID sessionId,Instant expires,Instant consumed) { }
    private record Outcome(LoginResult value,AuthFailure failure) {
        static Outcome ok(LoginResult value){return new Outcome(value,null);} static Outcome fail(AuthFailure failure){return new Outcome(null,failure);}
        LoginResult unwrap(){if(failure!=null)throw failure;return value;}
    }
    public record AccountView(UUID id,String role) { }
    public record LoginResult(boolean ok,AccountView account,TokenPair session) { @Override public String toString(){return "LoginResult[tokens redacted]";} }
    public record TokenPair(@JsonProperty("access_token") String accessToken,@JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") long expiresIn,@JsonProperty("expires_at") long expiresAt,
        @JsonProperty("token_type") String tokenType,@JsonProperty("refresh_expires_at") Instant refreshExpiresAt) {
        @Override public String toString(){return "TokenPair[redacted]";}
    }
}
