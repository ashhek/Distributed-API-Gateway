package com.apigateway.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * <h2>RequestLoggingFilter</h2>
 *
 * <p>A global pre/post filter that logs every request passing through the gateway.
 * Runs at the highest precedence ({@link Ordered#HIGHEST_PRECEDENCE}) so it
 * wraps all other filters and provides end-to-end latency measurement.</p>
 *
 * <h3>What it logs</h3>
 * <ul>
 *   <li><b>Inbound</b>: method, URI, client IP, correlation ID</li>
 *   <li><b>Outbound</b>: HTTP status, latency in milliseconds</li>
 * </ul>
 *
 * <h3>Correlation ID</h3>
 * <p>A {@code X-Correlation-Id} header is generated (or forwarded if already present)
 * so that logs across gateway and downstream services can be joined in a log aggregator
 * (e.g., ELK, Grafana Loki).</p>
 */
@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";
    private static final String START_TIME_ATTR = "requestStartTime";

    @Override
    public int getOrder() {
        // Runs before all other filters — ensures accurate latency tracking.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // ── Correlation ID ──────────────────────────────────────────────────
        String correlationId = request.getHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        final String finalCorrelationId = correlationId;

        // Mutate request to propagate correlation ID downstream
        ServerHttpRequest mutatedRequest = request.mutate()
                .header(CORRELATION_ID_HEADER, finalCorrelationId)
                .build();

        // Record start time for latency calculation
        exchange.getAttributes().put(START_TIME_ATTR, Instant.now().toEpochMilli());

        // ── Pre-filter log ───────────────────────────────────────────────────
        log.info("→ [{}] {} {} | client={}",
                finalCorrelationId,
                request.getMethod(),
                request.getURI(),
                getClientIp(request));

        // ── Chain + post-filter log ──────────────────────────────────────────
        return chain.filter(exchange.mutate().request(mutatedRequest).build())
                .then(Mono.fromRunnable(() -> {
                    ServerHttpResponse response = exchange.getResponse();
                    long startTime = (Long) exchange.getAttributes().getOrDefault(START_TIME_ATTR, 0L);
                    long latencyMs = Instant.now().toEpochMilli() - startTime;

                    log.info("← [{}] {} {} | status={} | latency={}ms",
                            finalCorrelationId,
                            request.getMethod(),
                            request.getURI(),
                            response.getStatusCode(),
                            latencyMs);
                }));
    }

    /**
     * Extracts the real client IP, accounting for reverse-proxy headers.
     * Priority: X-Forwarded-For → X-Real-IP → remote address.
     */
    private String getClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String xri = request.getHeaders().getFirst("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri;
        }
        return request.getRemoteAddress() != null
                ? request.getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
    }
}
