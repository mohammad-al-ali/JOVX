package com.jovx.api.security.internal.sessions;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("jovx.security.sessions")
public record AuthSettings(@NotNull Duration accessLifetime, @NotNull Duration refreshLifetime,
    @Min(1) int maxPinAttempts, @NotNull Duration pinLockDuration,
    @Min(1) int loginIpLimit, @NotNull Duration loginIpWindow,
    @Min(1) int tokenIpLimit, @NotNull Duration tokenIpWindow) {
    public AuthSettings {
        for (Duration value : new Duration[]{accessLifetime, refreshLifetime, pinLockDuration, loginIpWindow, tokenIpWindow}) {
            if (value != null && (value.isNegative() || value.isZero() || value.getSeconds() < 1)) {
                throw new IllegalArgumentException("Security durations must be at least one second");
            }
        }
        if (accessLifetime != null && refreshLifetime != null && accessLifetime.compareTo(refreshLifetime) > 0) {
            throw new IllegalArgumentException("Access lifetime cannot exceed refresh lifetime");
        }
    }
}
