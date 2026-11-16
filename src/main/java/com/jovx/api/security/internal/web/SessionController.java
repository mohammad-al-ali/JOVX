package com.jovx.api.security.internal.web;

import java.util.List;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SessionController {
    // Bootstrap identity inspection only. No login or business authorization is implemented here.
    @GetMapping("/api/v1/session")
    public SessionView session(JwtAuthenticationToken authentication) {
        return new SessionView(authentication.getToken().getSubject(),
            authentication.getAuthorities().stream().map(GrantedAuthority::getAuthority).sorted().toList());
    }

    public record SessionView(String subject, List<String> authorities) { }
}
