package com.apigateway.gateway.controller;

import com.apigateway.gateway.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * <h2>TokenController</h2>
 *
 * <p>A convenience endpoint for generating valid JWTs during development and
 * integration testing. This controller is intentionally <strong>not secured</strong>
 * — it is whitelisted in both {@code SecurityConfig} and {@code JwtAuthenticationFilter}
 * under the {@code /auth/**} prefix.</p>
 *
 * <h3>⚠ Production Warning</h3>
 * <p>This endpoint issues tokens for any {@code userId}+{@code role} combination
 * without authenticating the caller. In a real system this would be backed by a
 * user store (database + password hash) or an external IdP (Keycloak, Auth0, etc.).
 * For Phase 5, consider gating this endpoint behind an API key or removing it
 * entirely in favor of a dedicated auth service.</p>
 *
 * <h3>Available Endpoints</h3>
 * <ul>
 *   <li>{@code POST /auth/token}        — issue a custom token (body: userId, role)</li>
 *   <li>{@code GET  /auth/token/free}   — quick FREE-tier token for smoke testing</li>
 *   <li>{@code GET  /auth/token/pro}    — quick PRO-tier token for smoke testing</li>
 *   <li>{@code POST /auth/token/verify} — validate a token and show its decoded claims</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class TokenController {

    private final JwtUtil jwtUtil;

    // ── POST /auth/token ──────────────────────────────────────────────────────

    /**
     * Issues a JWT for a custom {@code userId} and {@code role}.
     *
     * <h4>Request body (JSON)</h4>
     * <pre>{@code
     * {
     *   "userId": "user-42",
     *   "role":   "PRO"
     * }
     * }</pre>
     *
     * <h4>Response (JSON)</h4>
     * <pre>{@code
     * {
     *   "token":     "eyJhbGci...",
     *   "userId":    "user-42",
     *   "role":      "PRO",
     *   "issuedAt":  "2025-01-01T00:00:00Z",
     *   "expiresAt": "2025-01-01T01:00:00Z"
     * }
     * }</pre>
     */
    @PostMapping("/token")
    public Mono<ResponseEntity<Map<String, Object>>> issueToken(
            @RequestBody Map<String, String> request) {

        String userId = request.getOrDefault("userId", "anonymous");
        String role   = request.getOrDefault("role", "FREE");

        return buildTokenResponse(userId, role);
    }

    // ── GET /auth/token/free ──────────────────────────────────────────────────

    /**
     * Quick smoke-test shortcut — issues a FREE-tier token for {@code test-user-free}.
     * <p>No request body needed — just {@code curl http://localhost:8080/auth/token/free}</p>
     */
    @GetMapping("/token/free")
    public Mono<ResponseEntity<Map<String, Object>>> freeToken() {
        return buildTokenResponse("test-user-free", "FREE");
    }

    // ── GET /auth/token/pro ───────────────────────────────────────────────────

    /**
     * Quick smoke-test shortcut — issues a PRO-tier token for {@code test-user-pro}.
     * <p>Useful for testing higher-bucket rate limits in Phase 3.</p>
     */
    @GetMapping("/token/pro")
    public Mono<ResponseEntity<Map<String, Object>>> proToken() {
        return buildTokenResponse("test-user-pro", "PRO");
    }

    // ── POST /auth/token/verify ───────────────────────────────────────────────

    /**
     * Decodes and validates a token, returning its claims.
     * Useful for debugging — shows exactly what the gateway sees inside a token.
     *
     * <h4>Request body</h4>
     * <pre>{@code {"token": "eyJhbGci..."} }</pre>
     */
    @PostMapping("/token/verify")
    public Mono<ResponseEntity<Map<String, Object>>> verifyToken(
            @RequestBody Map<String, String> request) {

        String token = request.get("token");

        if (token == null || token.isBlank()) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of(
                    "valid",   false,
                    "message", "Request body must contain a 'token' field"
            )));
        }

        if (!jwtUtil.isValid(token)) {
            return Mono.just(ResponseEntity.status(401).body(Map.of(
                    "valid",   false,
                    "message", "Token is invalid, expired, or tampered"
            )));
        }

        String userId = jwtUtil.extractUserId(token);
        String role   = jwtUtil.extractRole(token);
        String expiry = jwtUtil.extractExpiry(token).toInstant().toString();

        return Mono.just(ResponseEntity.ok(Map.of(
                "valid",     true,
                "userId",    userId,
                "role",      role,
                "expiresAt", expiry
        )));
    }

    // ── Shared builder ────────────────────────────────────────────────────────

    private Mono<ResponseEntity<Map<String, Object>>> buildTokenResponse(
            String userId, String role) {

        String token     = jwtUtil.generateToken(userId, role);
        String issuedAt  = Instant.now().toString();
        String expiresAt = jwtUtil.extractExpiry(token).toInstant().toString();

        log.info("Token issued | userId={} | role={} | expires={}", userId, role, expiresAt);

        return Mono.just(ResponseEntity.ok(Map.of(
                "token",     token,
                "userId",    userId,
                "role",      role,
                "issuedAt",  issuedAt,
                "expiresAt", expiresAt,
                "usage",     "Authorization: Bearer " + token
        )));
    }
}
