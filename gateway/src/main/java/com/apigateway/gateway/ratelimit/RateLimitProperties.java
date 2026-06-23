package com.apigateway.gateway.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

import org.springframework.cloud.context.config.annotation.RefreshScope;

/**
 * <h2>RateLimitProperties</h2>
 *
 * <p>Strongly-typed binding for the {@code rate-limit.*} YAML block.
 * Provides default limits and per-tier overrides used by
 * {@link RateLimiterFilterFactory} to select the correct bucket configuration
 * for each authenticated user.</p>
 *
 * <h3>YAML structure</h3>
 * <pre>
 * rate-limit:
 *   default:
 *     replenish-rate: 10        # tokens/sec for unknown/anonymous users
 *     burst-capacity: 20
 *   tiers:
 *     FREE:
 *       replenish-rate: 10
 *       burst-capacity: 20
 *     PRO:
 *       replenish-rate: 50
 *       burst-capacity: 100
 *     ENTERPRISE:
 *       replenish-rate: 200
 *       burst-capacity: 500
 * </pre>
 *
 * <h3>Phase 4 evolution</h3>
 * <p>In Phase 4 this entire block will be served by the Config Server and
 * hot-reloaded via {@code @RefreshScope} without a gateway restart.</p>
 */
@RefreshScope
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    /** Fallback limits used when no matching tier is found. */
    private TierConfig defaultConfig = new TierConfig(10, 20);

    /** Per-tier limit overrides. Key = tier name (e.g., "FREE", "PRO"). */
    private Map<String, TierConfig> tiers = new HashMap<>();

    // ── Accessors ─────────────────────────────────────────────────────────────

    public TierConfig getDefaultConfig() {
        return defaultConfig;
    }

    public void setDefaultConfig(TierConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    public Map<String, TierConfig> getTiers() {
        return tiers;
    }

    public void setTiers(Map<String, TierConfig> tiers) {
        this.tiers = tiers;
    }

    /**
     * Resolves the {@link TierConfig} for a given role string.
     * Falls back to {@link #defaultConfig} if the role has no explicit mapping.
     *
     * @param role the user's role (e.g., "FREE", "PRO", "ENTERPRISE")
     * @return the resolved {@link TierConfig}, never null
     */
    public TierConfig resolveForRole(String role) {
        if (role == null || role.isBlank()) {
            return defaultConfig;
        }
        return tiers.getOrDefault(role.toUpperCase(), defaultConfig);
    }

    // ── Inner class ───────────────────────────────────────────────────────────

    /**
     * Per-tier rate limit configuration.
     * Both fields map directly to the Lua script's ARGV inputs.
     */
    public static class TierConfig {

        /** Tokens added to the bucket per second (continuous refill rate). */
        private int replenishRate;

        /** Maximum tokens the bucket can hold (burst allowance). */
        private int burstCapacity;

        public TierConfig() {}

        public TierConfig(int replenishRate, int burstCapacity) {
            this.replenishRate = replenishRate;
            this.burstCapacity = burstCapacity;
        }

        public int getReplenishRate() { return replenishRate; }
        public void setReplenishRate(int replenishRate) { this.replenishRate = replenishRate; }

        public int getBurstCapacity() { return burstCapacity; }
        public void setBurstCapacity(int burstCapacity) { this.burstCapacity = burstCapacity; }

        @Override
        public String toString() {
            return "TierConfig{replenishRate=" + replenishRate +
                   ", burstCapacity=" + burstCapacity + "}";
        }
    }
}
