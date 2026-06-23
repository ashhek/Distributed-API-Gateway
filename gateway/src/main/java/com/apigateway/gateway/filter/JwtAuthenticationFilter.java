package com.apigateway.gateway.filter;

import com.apigateway.gateway.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * <h2>JwtAuthenticationFilter</h2>
 *
 * <p>A global pre-filter that intercepts every inbound request and enforces
 * JWT authentication before traffic reaches any downstream service.</p>
 *
 * <h3>Execution Order</h3>
 * <p>Runs at order {@code -1}, giving it the highest effective priority
 * among custom filters (just below {@link RequestLoggingFilter} at
 * {@link Ordered#HIGHEST_PRECEDENCE}). This guarantees authentication
 * happens before any routing decision.</p>
 *
 * <h3>Happy Path</h3>
 * <ol>
 *   <li>Extracts the Bearer token from {@code Authorization} header.</li>
 *   <li>Validates signature + expiry via {@link JwtUtil}.</li>
 *   <li>Injects {@code X-User-Id} and {@code X-User-Role} headers into the
 *       forwarded request — downstream services and the Phase 3 rate limiter
 *       can read identity without re-parsing the JWT.</li>
 *   <li>Passes the mutated exchange to the next filter in the chain.</li>
 * </ol>
 *
 * <h3>Rejection Path</h3>
 * <p>Returns {@code HTTP 401 Unauthorized} with a JSON error body if:</p>
 * <ul>
 *   <li>The {@code Authorization} header is missing</li>
 *   <li>The header does not start with {@code Bearer }</li>
 *   <li>The token is expired, tampered, or malformed</li>
 * </ul>
 *
 * <h3>Public Route Whitelist</h3>
 * <p>Paths in {@link #PUBLIC_PATHS} bypass authentication entirely.
 * These are safe to expose without a token (health probes, actuator,
 * gateway info, and the test token endpoint).</p>
 *
 * <h3>Phase 3 Hook</h3>
 * <p>After this filter runs, the rate limiter filter (Phase 3) reads
 * {@code X-User-Id} and {@code X-User-Role} from the forwarded request
 * headers to key its Redis token bucket — no JWT re-parsing needed.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String BEARER_PREFIX   = "Bearer ";
    /** Header injected downstream so services know which user made the request */
    public  static final String HEADER_USER_ID  = "X-User-Id";
    /** Header injected downstream for RBAC and rate-limit tier lookup */
    public  static final String HEADER_USER_ROLE = "X-User-Role";

    /**
     * Routes that bypass JWT authentication.
     *
     * <ul>
     *   <li>{@code /api/health/**}     — infrastructure health probes</li>
     *   <li>{@code /actuator/**}       — Spring Boot actuator endpoints</li>
     *   <li>{@code /gateway/**}        — gateway self-info / admin (Phase 5)</li>
     *   <li>{@code /auth/**}           — token issuance endpoint (this phase)</li>
     * </ul>
     */
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/health",
            "/actuator",
            "/gateway",
            "/auth"
    );

    private final JwtUtil jwtUtil;

    // ── GlobalFilter ──────────────────────────────────────────────────────────

    @Override
    public int getOrder() {
        // Run after RequestLoggingFilter (HIGHEST_PRECEDENCE = Integer.MIN_VALUE)
        // but before all route filters (-1 is a high priority custom filter slot)
        return -1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // ── 1. Whitelist check ────────────────────────────────────────────────
        if (isPublicPath(path)) {
            log.debug("Public path — skipping auth: {}", path);
            return chain.filter(exchange);
        }

        // ── 2. Extract Authorization header ───────────────────────────────────
        String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (!StringUtils.hasText(authHeader) || !authHeader.startsWith(BEARER_PREFIX)) {
            log.warn("Missing or malformed Authorization header | path={}", path);
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "Missing or malformed Authorization header. " +
                    "Expected: 'Authorization: Bearer <token>'");
        }

        // ── 3. Extract and validate the token ─────────────────────────────────
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        if (!jwtUtil.isValid(token)) {
            log.warn("Invalid JWT | path={}", path);
            return reject(exchange, HttpStatus.UNAUTHORIZED,
                    "JWT token is invalid, expired, or tampered.");
        }

        // ── 4. Extract claims ─────────────────────────────────────────────────
        String userId = jwtUtil.extractUserId(token);
        String role   = jwtUtil.extractRole(token);

        log.debug("JWT validated | userId={} | role={} | path={}", userId, role, path);

        // ── 5. Mutate request — inject identity headers ───────────────────────
        // Downstream services (and Phase 3 rate limiter) read X-User-Id / X-User-Role.
        // We REMOVE the original Authorization header from the forwarded request
        // so the downstream service never handles raw JWTs (defense-in-depth).
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(HEADER_USER_ID,   userId)
                .header(HEADER_USER_ROLE, role)
                .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                .build();

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Checks whether the request path starts with any whitelisted prefix.
     */
    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(path::startsWith);
    }

    /**
     * Short-circuits the filter chain with an HTTP error response.
     *
     * <p>Returns a JSON body so API clients get machine-readable errors:</p>
     * <pre>{@code
     * {
     *   "status": 401,
     *   "error": "Unauthorized",
     *   "message": "..."
     * }
     * }</pre>
     */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"status\":%d,\"error\":\"%s\",\"message\":\"%s\"}",
                status.value(),
                status.getReasonPhrase(),
                message
        );

        byte[] bytes = body.getBytes();
        return response.writeWith(
                Mono.just(response.bufferFactory().wrap(bytes))
        );
    }
}
