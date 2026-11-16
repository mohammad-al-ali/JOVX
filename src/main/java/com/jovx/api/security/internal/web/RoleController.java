package com.jovx.api.security.internal.web;

import com.jovx.api.security.internal.roles.RoleManagement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("local-postgres") @PreAuthorize("hasRole('SUPER_ADMIN')")
public class RoleController {
    private final RoleManagement roles;
    public RoleController(RoleManagement roles){this.roles=roles;}
    @GetMapping("/api/v1/admin/access/catalog")
    public Map<String,Object> catalog(JwtAuthenticationToken actor){return roles.catalog(id(actor),sid(actor));}
    @PutMapping("/api/v1/admin/roles/{key}/permissions") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void permissions(JwtAuthenticationToken actor,@PathVariable String key,@Valid @RequestBody Grants body){roles.replace(id(actor),sid(actor),key,body.permissions());}
    @PutMapping("/api/v1/admin/users/{userId}/role") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void assign(JwtAuthenticationToken actor,@PathVariable UUID userId,@Valid @RequestBody Assignment body){roles.assign(id(actor),sid(actor),userId,body.roleKey());}
    public record Grants(@NotNull @Size(max=100) Set<@NotBlank String> permissions){}
    public record Assignment(@NotBlank @Size(max=32) String roleKey){}
    private static UUID id(JwtAuthenticationToken a){return UUID.fromString(a.getName());}
    private static UUID sid(JwtAuthenticationToken a){return UUID.fromString(a.getToken().getClaimAsString("sid"));}
}
