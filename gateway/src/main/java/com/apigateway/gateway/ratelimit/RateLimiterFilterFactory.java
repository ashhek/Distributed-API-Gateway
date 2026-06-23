package com.apigateway.gateway.ratelimit;

import com.apigateway.gateway.filter.JwtAuthenticationFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

/**
 * <h2>RateLimiterFilterFactory</h2>
 *
 * <p>A custom Spring Cloud Gateway {@link AbstractGatewayFilterFactory} that
 * enforces per-user, per-route rate limits using the Token Bucket algorithm
 * executed atomically in Redis via a Lua script.</p>
 *
 * <h3>How it works end-to-end</h3>
 * <ol>
 *   <li><b>Key resolution</b> — reads {@code X-User-Id} injected by
 *       {@link JwtAuthenticationFilter} in Phase 2. Falls back to the client
 *       IP address for public/anonymous routes.</li>
 *   <li><b>Tier resolution</b> — reads {@code X-User-Role} and looks up the
 *       matching {@link RateLimitProperties.TierConfig} (replenish rate + burst).</li>
 *   <li><b>Lua execution</b> — calls {@code EVAL} on the Token Bucket script
 *       via {@link ReactiveStringRedisTemplate}. The entire operation is atomic.</li>
 *   <li><b>Decision</b>:
 *     <ul>
 *       <li>Allowed  → injects rate-limit headers and forwards the request.</li>
 *       <li>Rejected → returns {@code 429 Too Many Requests} immediately.</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <h3>Redis Key Design</h3>
 * <pre>
 *   Tokens key  : rl:{userId}:{normalizedPath}:tokens
 *   Timestamp key: rl:{userId}:{normalizedPath}:ts
 * </pre>
 * <p>Using both {@code userId} AND route path makes this truly multi-dimensional:
 * a PRO user is rate-limited separately on {@code /api/data} vs {@code /api/reports}.</p>
 *
 * <h3>Standard Rate-Limit Response Headers</h3>
 * <ul>
 *   <li>{@code X-RateLimit-Limit}     — burst capacity (max tokens)</li>
 *   <li>{@code X-RateLimit-Remaining} — tokens left after this request</li>
 *   <li>{@code X-RateLimit-Retry-After} — seconds to wait before retrying (on 429)</li>
 * </ul>
 *
 * <h3>Redis Unavailability Handling</h3>
 * <p>If Redis is down, the filter logs the error and <em>allows</em> the request
 * through (fail-open strategy). This prevents a Redis outage from taking down the
 * entire API. Change to fail-closed by returning 503 in the error handler for
 * stricter SLAs.</p>
 *
 * <h3>Phase 4 Hook</h3>
 * <p>{@link RateLimitProperties} is already annotated with {@code @RefreshScope}
 * awareness — once the Config Server is wired in Phase 4, tier limits can be
 * changed without restarting the gateway.</p>
 */
@Slf4j
@Component
public class RateLimiterFilterFactory
        extends AbstractGatewayFilterFactory<RateLimiterFilterFactory.Config> {

    // ── Header constants ──────────────────────────────────────────────────────
    public static final String HEADER_LIMIT       = "X-RateLimit-Limit";
    public static final String HEADER_REMAINING   = "X-RateLimit-Remaining";
    public static final String HEADER_RETRY_AFTER = "X-RateLimit-Retry-After";

    // ── Lua result indices ────────────────────────────────────────────────────
    private static final int IDX_ALLOWED   = 0;
    private static final int IDX_REMAINING = 1;
    private static final int IDX_CAPACITY  = 2;

    private final ReactiveStringRedisTemplate redisTemplate;
    private final DefaultRedisScript<List<Long>> rateLimiterScript;
    private final RateLimitProperties props;
    private final MeterRegistry meterRegistry;

    public RateLimiterFilterFactory(
            ReactiveStringRedisTemplate redisTemplate,
            DefaultRedisScript<List<Long>> rateLimiterScript,
            RateLimitProperties props,
            MeterRegistry meterRegistry) {
        super(Config.class);
        this.redisTemplate      = redisTemplate;
        this.rateLimiterScript  = rateLimiterScript;
        this.props              = props;
        this.meterRegistry      = meterRegistry;
    }

    // ── GatewayFilterFactory contract ─────────────────────────────────────────

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> doRateLimit(exchange, chain, config);
    }

    // ── Core filter logic ─────────────────────────────────────────────────────

    private Mono<Void> doRateLimit(ServerWebExchange exchange,
                                   GatewayFilterChain chain,
                                   Config config) {
        ServerHttpRequest request = exchange.getRequest();

        // ── 1. Resolve identity key ───────────────────────────────────────────
        String userId = request.getHeaders().getFirst(JwtAuthenticationFilter.HEADER_USER_ID);
        String role   = request.getHeaders().getFirst(JwtAuthenticationFilter.HEADER_USER_ROLE);

        // Fall back to client IP for anonymous / public-route traffic
        if (userId == null || userId.isBlank()) {
            userId = resolveClientIp(request);
            role   = "ANONYMOUS";
        }

        // ── 2. Resolve tier config ────────────────────────────────────────────
        // Config can override tier props (per-route customisation)
        // or fall through to role-based defaults from application.yml.
        int replenishRate;
        int burstCapacity;

        if (config.getReplenishRate() > 0) {
            // Explicit per-route override takes highest priority
            replenishRate = config.getReplenishRate();
            burstCapacity = config.getBurstCapacity();
        } else {
            // Resolve from role (Phase 3: from application.yml; Phase 4: from config server)
            RateLimitProperties.TierConfig tier = props.resolveForRole(role);
            replenishRate = tier.getReplenishRate();
            burstCapacity = tier.getBurstCapacity();
        }

        // ── 3. Build Redis keys ───────────────────────────────────────────────
        // Normalize path: strip trailing slash, collapse to route root for wildcards
        String normalizedPath = normalizePath(request.getURI().getPath());
        String baseKey        = "rl:" + userId + ":" + normalizedPath;
        List<String> keys     = Arrays.asList(baseKey + ":tokens", baseKey + ":ts");

        // ── 4. Build Lua ARGV ─────────────────────────────────────────────────
        long nowMs = System.currentTimeMillis();
        List<String> args = Arrays.asList(
                String.valueOf(replenishRate),  // ARGV[1]: tokens/sec
                String.valueOf(burstCapacity),  // ARGV[2]: max burst
                String.valueOf(nowMs),          // ARGV[3]: current time (ms)
                "1"                             // ARGV[4]: tokens requested
        );

        final String finalUserId       = userId;
        final int    finalReplenish    = replenishRate;
        final int    finalBurst        = burstCapacity;

        // ── 5. Execute Lua script reactively ─────────────────────────────────
        // DefaultRedisScript<List<Long>> causes ReactiveRedisTemplate.execute()
        // to emit exactly ONE item of type List<Long> per EVAL call.
        // collectList() wraps it into List<List<Long>> — we unwrap with .get(0).
        return redisTemplate
                .execute(rateLimiterScript, keys, args)
                .collectList()
                .flatMap(results -> {
                    if (results == null || results.isEmpty()) {
                        log.warn("Empty result from rate limiter script for userId={} — allowing",
                                finalUserId);
                        return chain.filter(exchange); // fail-open
                    }

                    // results.get(0) is the List<Long> returned by the Lua script:
                    // index 0 = allowed (1/0), index 1 = remaining tokens, index 2 = capacity
                    @SuppressWarnings("unchecked")
                    List<Long> result = (List<Long>) results.get(0);
                    if (result == null || result.size() < 3) {
                        log.warn("Malformed result from rate limiter script: {} — allowing", results);
                        return chain.filter(exchange); // fail-open
                    }

                    // Redis integers come back as java.lang.Long but cast via Number for safety
                    boolean allowed   = ((Number) result.get(IDX_ALLOWED)).longValue() == 1L;
                    long    remaining = ((Number) result.get(IDX_REMAINING)).longValue();
                    long    capacity  = ((Number) result.get(IDX_CAPACITY)).longValue();

                    log.debug("RateLimit | userId={} | path={} | allowed={} | remaining={}/{}",
                            finalUserId, normalizedPath, allowed, remaining, capacity);

                    if (allowed) {
                        meterRegistry.counter("gateway.ratelimit.requests", "status", "allowed").increment();
                        // ── 6a. Allowed — add informational headers ───────────
                        exchange.getResponse().getHeaders()
                                .add(HEADER_LIMIT,     String.valueOf(capacity));
                        exchange.getResponse().getHeaders()
                                .add(HEADER_REMAINING, String.valueOf(remaining));
                        return chain.filter(exchange);
                    } else {
                        meterRegistry.counter("gateway.ratelimit.requests", "status", "rejected").increment();
                        // ── 6b. Rejected — 429 Too Many Requests ─────────────
                        return reject(exchange, finalBurst, finalReplenish);
                    }
                })
                .onErrorResume(ex -> {
                    // Redis is unavailable — fail-open to avoid cascading failure
                    meterRegistry.counter("gateway.ratelimit.requests", "status", "error_bypass").increment();
                    log.error("Rate limiter Redis error for userId={} — allowing request: {}",
                            finalUserId, ex.getMessage());
                    return chain.filter(exchange);
                });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Normalizes the request path into a stable Redis key segment.
     *
     * <ul>
     *   <li>Strips trailing slashes</li>
     *   <li>Replaces non-alphanumeric characters (except {@code /}) with {@code _}
     *       to avoid Redis key injection via crafted paths</li>
     *   <li>Collapses dynamic segments: {@code /api/data/123} → {@code /api/data}
     *       so all IDs on the same endpoint share one bucket</li>
     * </ul>
     */
    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "root";
        // Collapse to first two segments (/api/data/123 → /api/data)
        String[] segments = path.split("/");
        StringBuilder normalized = new StringBuilder();
        int count = 0;
        for (String seg : segments) {
            if (!seg.isBlank() && count < 2) {
                normalized.append("/").append(seg.replaceAll("[^a-zA-Z0-9]", "_"));
                count++;
            }
        }
        return normalized.length() > 0 ? normalized.toString() : "root";
    }

    /**
     * Extracts the real client IP, respecting reverse-proxy headers.
     * Priority: {@code X-Forwarded-For} → {@code X-Real-IP} → remote address.
     */
    private String resolveClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim().replaceAll("[^a-zA-Z0-9._:-]", "_");
        }
        String xri = request.getHeaders().getFirst("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim().replaceAll("[^a-zA-Z0-9._:-]", "_");
        }
        InetSocketAddress remoteAddr = request.getRemoteAddress();
        return remoteAddr != null
                ? remoteAddr.getAddress().getHostAddress().replaceAll("[^a-zA-Z0-9._:-]", "_")
                : "unknown";
    }

    /**
     * Returns {@code 429 Too Many Requests} with a JSON body and standard headers.
     *
     * <p>The {@code Retry-After} value is derived from the replenish rate:
     * if the bucket refills 10 tokens/sec and a full request costs 1 token,
     * the client should wait approximately {@code 1 / replenishRate} seconds —
     * we round up to 1 second minimum for simplicity.</p>
     */
    private Mono<Void> reject(ServerWebExchange exchange, int burstCapacity, int replenishRate) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().add(HEADER_LIMIT,       String.valueOf(burstCapacity));
        response.getHeaders().add(HEADER_REMAINING,   "0");
        response.getHeaders().add(HEADER_RETRY_AFTER, String.valueOf(Math.max(1, 1 / replenishRate)));

        String body = String.format(
                "{\"status\":429,\"error\":\"Too Many Requests\"," +
                "\"message\":\"Rate limit exceeded. Please slow down.\"," +
                "\"retryAfterSeconds\":%d}",
                Math.max(1, 1 / replenishRate)
        );

        byte[] bytes = body.getBytes();
        return response.writeWith(Mono.just(response.bufferFactory().wrap(bytes)));
    }

    // ── Config inner class ────────────────────────────────────────────────────

    /**
     * Per-route filter configuration injected via the Java DSL:
     * <pre>
     * .filter(rateLimiterFilter.apply(c -> {
     *     c.setReplenishRate(50);   // override rate for this specific route
     *     c.setBurstCapacity(100);
     * }))
     * </pre>
     *
     * <p>When {@code replenishRate == 0} (default), the filter falls back to
     * the user's role-based tier from {@link RateLimitProperties}.</p>
     */
    public static class Config {
        /** Tokens per second override. 0 = use role-based tier from properties. */
        private int replenishRate = 0;
        /** Max burst override. 0 = use role-based tier from properties. */
        private int burstCapacity = 0;

        public int getReplenishRate() { return replenishRate; }
        public void setReplenishRate(int replenishRate) { this.replenishRate = replenishRate; }

        public int getBurstCapacity() { return burstCapacity; }
        public void setBurstCapacity(int burstCapacity) { this.burstCapacity = burstCapacity; }
    }
}
