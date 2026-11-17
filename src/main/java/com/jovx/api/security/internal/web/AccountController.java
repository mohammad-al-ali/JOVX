package com.jovx.api.security.internal.web;

import com.jovx.api.security.internal.sessions.AuthSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

/** Own-PIN change. Approved for PARTNER only; employees' PINs stay owning-Partner-only. */
@RestController @Profile("local-postgres")
public class AccountController {
    private final AuthSessionService sessions;
    public AccountController(AuthSessionService sessions){this.sessions=sessions;}
    @PutMapping("/api/v1/account/pin") @ResponseStatus(HttpStatus.NO_CONTENT) @PreAuthorize("hasRole('PARTNER')")
    public void changePin(JwtAuthenticationToken actor,@Valid @RequestBody PinChange body){
        sessions.changeOwnPin(UUID.fromString(actor.getName()),UUID.fromString(actor.getToken().getClaimAsString("sid")),body.currentPin(),body.newPin());
    }
    public record PinChange(@NotNull @Pattern(regexp="[0-9]{6}") String currentPin,@NotNull @Pattern(regexp="[0-9]{6}") String newPin){
        @Override public String toString(){return "PinChange[redacted]";}
    }
}
