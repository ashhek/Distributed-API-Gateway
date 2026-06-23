package com.apigateway.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * <h2>JwtProperties</h2>
 *
 * <p>Strongly-typed binding for the {@code jwt.*} block in {@code application.yml}.
 * Using {@link ConfigurationProperties} over {@code @Value} gives us:</p>
 * <ul>
 *   <li>IDE auto-completion and metadata generation</li>
 *   <li>A single, immutable object to pass around instead of scattered strings</li>
 *   <li>Easy validation with JSR-303 in later phases</li>
 * </ul>
 *
 * <h3>YAML binding</h3>
 * <pre>
 * jwt:
 *   secret: "..."        → {@link #secret}
 *   expiration-ms: 3600000 → {@link #expirationMs}
 * </pre>
 *
 * <h3>Security Note</h3>
 * <p>The secret must be at least 256 bits (32 bytes) for HMAC-SHA256.
 * In production, inject via {@code JWT_SECRET} environment variable —
 * never hard-code in source control.</p>
 */
@Component
@ConfigurationProperties(prefix = "jwt")
public class JwtProperties {

    /**
     * HMAC-SHA256 signing secret. Must be ≥ 32 characters in UTF-8.
     * Loaded from {@code JWT_SECRET} env var in production.
     */
    private String secret;

    /**
     * Token time-to-live in milliseconds. Default: 3 600 000 ms (1 hour).
     * Used both when issuing tokens (TokenController) and when validating expiry.
     */
    private long expirationMs;

    // ── Getters & Setters ─────────────────────────────────────────────────────

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public long getExpirationMs() {
        return expirationMs;
    }

    public void setExpirationMs(long expirationMs) {
        this.expirationMs = expirationMs;
    }
}
