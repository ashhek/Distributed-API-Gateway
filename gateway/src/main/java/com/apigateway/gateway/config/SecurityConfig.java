package com.apigateway.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * <h2>SecurityConfig — Phase 2 (JWT-enforced)</h2>
 *
 * <p>Spring Security WebFlux configuration. The actual JWT validation is done
 * by {@link com.apigateway.gateway.filter.JwtAuthenticationFilter} (a
 * {@code GlobalFilter} that runs before the security chain). Spring Security
 * here acts as the enforcement layer — declaring which paths are public and
 * which require authentication — while the filter does the real crypto work.</p>
 *
 * <h3>Why not use Spring Security's JWT DSL?</h3>
 * <p>Spring Security's built-in OAuth2 resource server ({@code .oauth2ResourceServer()})
 * is designed for OIDC / opaque token flows. Our custom Token Bucket rate limiter
 * (Phase 3) needs {@code userId} and {@code role} injected as request headers
 * <em>before</em> routing — a use-case that's easier to implement as a
 * {@link com.apigateway.gateway.filter.JwtAuthenticationFilter} than inside
 * the Security filter chain.</p>
 *
 * <h3>Public Routes</h3>
 * <ul>
 *   <li>{@code /auth/**}     — token issuance (no token needed to get a token)</li>
 *   <li>{@code /api/health/**} — infrastructure probes</li>
 *   <li>{@code /actuator/**} — Spring Boot actuator</li>
 *   <li>{@code /gateway/**}  — gateway admin info</li>
 * </ul>
 *
 * <h3>Phase 3 Note</h3>
 * <p>No changes needed here for Phase 3 — the rate limiter filter reads the
 * {@code X-User-Id} header injected by {@code JwtAuthenticationFilter}.</p>
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                // CSRF disabled: stateless Bearer-token API
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // No session — every request carries its own token
                .authorizeExchange(exchanges -> exchanges
                        // ── Public endpoints (no JWT required) ──────────────
                        .pathMatchers(
                                "/auth/**",
                                "/api/health/**",
                                "/actuator/**",
                                "/gateway/**"
                        ).permitAll()

                        // ── All other routes require authentication ──────────
                        // The actual validation is done by JwtAuthenticationFilter.
                        // Spring Security permits the request here because the
                        // filter has already run and will have rejected invalid tokens
                        // before they reach the route handler.
                        .anyExchange().permitAll()  // Filter handles the 401, not SS
                )
                // Disable form login and HTTP Basic — Bearer token only
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .build();
    }
}
