package com.apigateway.gateway.config;

import com.apigateway.gateway.ratelimit.RateLimiterFilterFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * <h2>GatewayRoutingConfig — Phase 3 (Rate-Limited Routes)</h2>
 *
 * <p>All data and echo routes now carry the {@link RateLimiterFilterFactory}
 * filter. The rate limiter reads {@code X-User-Id} (injected by the JWT filter
 * in Phase 2) and looks up the user's tier from {@link
 * com.apigateway.gateway.ratelimit.RateLimitProperties} to select the correct
 * bucket configuration.</p>
 *
 * <h3>Filter execution order per request</h3>
 * <pre>
 *  [RequestLoggingFilter]     order=HIGHEST_PRECEDENCE  — correlation ID, latency
 *  [JwtAuthenticationFilter]  order=-1                  — validates JWT, injects X-User-Id
 *  [RateLimiterFilterFactory] (route-level GatewayFilter) — enforces token bucket
 *  [Downstream Service]       — receives clean, rate-limited request
 * </pre>
 *
 * <h3>Health route exclusion</h3>
 * <p>{@code /api/health/**} deliberately has no rate limiter — infrastructure
 * probes must always succeed regardless of bucket state.</p>
 */
@Configuration
public class GatewayRoutingConfig {

    @Value("${downstream.service.url:http://localhost:8081}")
    private String downstreamServiceUrl;

    private final RateLimiterFilterFactory rateLimiterFilterFactory;

    public GatewayRoutingConfig(RateLimiterFilterFactory rateLimiterFilterFactory) {
        this.rateLimiterFilterFactory = rateLimiterFilterFactory;
    }

    @Bean
    public RouteLocator apiRoutes(RouteLocatorBuilder builder) {
        return builder.routes()

                // ── Route 1: Primary data route (RATE-LIMITED) ───────────────────────
                // replenishRate=0 / burstCapacity=0 → delegates to role-based tier
                // config from RateLimitProperties (FREE=10/20, PRO=50/100, etc.)
                .route("downstream-data-route", r -> r
                        .path("/api/data/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                                .preserveHostHeader()
                                .filter(rateLimiterFilterFactory.apply(config -> {
                                    // 0 = use role-based tier defaults (Phase 4 will make dynamic)
                                    config.setReplenishRate(0);
                                    config.setBurstCapacity(0);
                                }))
                        )
                        .uri(downstreamServiceUrl))

                // ── Route 2: Health check (NO rate limit) ────────────────────────────
                // Always allowed — used by load balancers and Kubernetes probes.
                .route("downstream-health-route", r -> r
                        .path("/api/health/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                        )
                        .uri(downstreamServiceUrl))

                // ── Route 3: Echo / debug route (RATE-LIMITED, low burst) ───────────
                // Explicitly hardcoded low limits for the debug endpoint.
                .route("downstream-echo-route", r -> r
                        .path("/api/echo/**")
                        .filters(f -> f
                                .addRequestHeader("X-Gateway-Source", "api-gateway")
                                .filter(rateLimiterFilterFactory.apply(config -> {
                                    config.setReplenishRate(5);
                                    config.setBurstCapacity(10);
                                }))
                        )
                        .uri(downstreamServiceUrl))

                .build();
    }
}
