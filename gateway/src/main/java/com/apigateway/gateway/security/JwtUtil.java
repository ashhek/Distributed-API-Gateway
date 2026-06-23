package com.apigateway.gateway.security;

import com.apigateway.gateway.config.JwtProperties;
import io.jsonwebtoken.*;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;

/**
 * <h2>JwtUtil</h2>
 *
 * <p>Central utility for all JWT operations in the gateway: signing, parsing,
 * validation, and claim extraction. Built on JJWT 0.12.x (the fluent builder API).</p>
 *
 * <h3>Algorithm</h3>
 * <p>HMAC-SHA256 ({@code HS256}) — symmetric, no PKI needed for a single-service
 * gateway. For a microservice mesh where multiple services must verify tokens
 * independently, switch to RS256 (asymmetric) in Phase 4.</p>
 *
 * <h3>Claims Contract</h3>
 * <p>Every token issued by this gateway carries:</p>
 * <ul>
 *   <li>{@code sub}   — userId (the authenticated principal identifier)</li>
 *   <li>{@code role}  — user tier: {@code FREE}, {@code PRO}, or {@code ENTERPRISE}</li>
 *   <li>{@code iat}   — issued-at timestamp</li>
 *   <li>{@code exp}   — expiry timestamp ({@code iat + expirationMs})</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is a Spring {@code @Component} (singleton). The {@link SecretKey}
 * is initialized once in the constructor and is immutable — safe for concurrent use.</p>
 */
@Slf4j
@Component
public class JwtUtil {

    /** Claim key for the user's tier / role */
    public static final String CLAIM_ROLE = "role";

    private final SecretKey signingKey;
    private final long expirationMs;

    /**
     * Derives the HMAC-SHA256 {@link SecretKey} from the configured secret string.
     *
     * <p>JJWT's {@code Keys.hmacShaKeyFor()} verifies the key is at least 256 bits
     * and will throw {@link io.jsonwebtoken.security.WeakKeyException} at startup if
     * the secret is too short — fail-fast behavior that prevents misconfigured deployments.</p>
     */
    public JwtUtil(JwtProperties props) {
        byte[] keyBytes;
        try {
            // Try Base64-decoding first (preferred for production secrets)
            keyBytes = Decoders.BASE64.decode(props.getSecret());
        } catch (IllegalArgumentException e) {
            // Fall back to raw UTF-8 bytes (useful for plain dev secrets)
            keyBytes = props.getSecret().getBytes();
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = props.getExpirationMs();
    }

    // ── Token Generation ──────────────────────────────────────────────────────

    /**
     * Issues a signed JWT for the given user.
     *
     * @param userId   the principal identifier (stored in {@code sub} claim)
     * @param role     the user tier (e.g., "FREE", "PRO", "ENTERPRISE")
     * @return a compact, signed JWT string (header.payload.signature)
     */
    public String generateToken(String userId, String role) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(userId)
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)           // HS256 inferred from key type
                .compact();
    }

    // ── Token Validation ──────────────────────────────────────────────────────

    /**
     * Validates a JWT string.
     *
     * <p>Checks, in order:</p>
     * <ol>
     *   <li>Signature is valid (tamper detection)</li>
     *   <li>Token has not expired ({@code exp} claim)</li>
     *   <li>Token is well-formed (not malformed / truncated)</li>
     * </ol>
     *
     * @param token the raw JWT string (without the "Bearer " prefix)
     * @return {@code true} if all checks pass, {@code false} otherwise
     */
    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (ExpiredJwtException e) {
            log.warn("JWT expired: {}", e.getMessage());
        } catch (SignatureException e) {
            log.warn("JWT signature invalid: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            log.warn("JWT malformed: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            log.warn("JWT algorithm unsupported: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            log.warn("JWT empty or null: {}", e.getMessage());
        }
        return false;
    }

    // ── Claim Extraction ──────────────────────────────────────────────────────

    /**
     * Extracts the {@code sub} (subject / userId) claim.
     *
     * <p>Only call after {@link #isValid(String)} returns {@code true}.</p>
     */
    public String extractUserId(String token) {
        return parseClaims(token).getSubject();
    }

    /**
     * Extracts the {@code role} custom claim.
     *
     * <p>Only call after {@link #isValid(String)} returns {@code true}.</p>
     */
    public String extractRole(String token) {
        return parseClaims(token).get(CLAIM_ROLE, String.class);
    }

    /**
     * Extracts the token expiry as a {@link Date}.
     * Useful for logging remaining TTL in debug scenarios.
     */
    public Date extractExpiry(String token) {
        return parseClaims(token).getExpiration();
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Parses the JWT and returns the {@link Claims} payload.
     * Throws JJWT runtime exceptions on any validation failure.
     */
    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
