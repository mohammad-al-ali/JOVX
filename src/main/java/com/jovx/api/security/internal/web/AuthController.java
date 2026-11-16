package com.jovx.api.security.internal.web;

import com.jovx.api.security.internal.sessions.AuthSessionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("local-postgres")
public class AuthController {
    private final AuthSessionService sessions;
    public AuthController(AuthSessionService sessions){this.sessions=sessions;}
    @PostMapping("/api/v1/auth/login")
    public AuthSessionService.LoginResult login(@Valid @RequestBody Login body){return sessions.login(body.username(),body.password());}
    @PostMapping("/api/v1/auth/refresh")
    public AuthSessionService.LoginResult refresh(@Valid @RequestBody Refresh body){return sessions.refresh(body.refreshToken());}
    @PostMapping("/api/v1/auth/logout") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody Refresh body){sessions.logout(body.refreshToken());}
    public record Login(@NotBlank @Size(max=64) String username,@NotNull @Pattern(regexp="[0-9]{6}") String password){
        @Override public String toString(){return "Login[redacted]";}
    }
    public record Refresh(@com.fasterxml.jackson.annotation.JsonProperty("refresh_token") @NotBlank @Size(max=128) String refreshToken){
        @Override public String toString(){return "Refresh[redacted]";}
    }
}
