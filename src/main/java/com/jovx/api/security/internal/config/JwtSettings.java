package com.jovx.api.security.internal.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("jovx.security.jwt")
public record JwtSettings(@NotBlank String issuer, @NotBlank String audience,
                          @NotNull Resource publicKey) { }
