package com.neracalab.backend.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Login settings ({@code neracalab.auth.*}).
 *
 * @param sessionTtl   how long a login (session token) is valid
 * @param rootPassword password of the root user {@code admin} when it is created (first start only;
 *                     never applied to an existing user)
 */
@ConfigurationProperties("neracalab.auth")
public record AuthProperties(@DefaultValue("12h") Duration sessionTtl, @DefaultValue("admin") String rootPassword) {

    public AuthProperties {
        if (sessionTtl.isNegative() || sessionTtl.isZero()) {
            throw new IllegalArgumentException("neracalab.auth.session-ttl must be positive");
        }
        if (rootPassword == null || rootPassword.isEmpty()) {
            throw new IllegalArgumentException("neracalab.auth.root-password must not be empty");
        }
    }
}
