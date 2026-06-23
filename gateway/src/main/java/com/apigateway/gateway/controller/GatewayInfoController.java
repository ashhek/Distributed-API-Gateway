package com.apigateway.gateway.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;

/**
 * <h2>GatewayInfoController</h2>
 *
 * <p>A lightweight admin endpoint exposed by the gateway itself (not proxied downstream).
 * Useful for verifying the gateway is running, checking version, and as a health probe
 * target for Kubernetes liveness checks that bypass the actuator.</p>
 *
 * <p>In Phase 5 this controller will be expanded with:</p>
 * <ul>
 *   <li>Current rate-limit policy view ({@code GET /gateway/admin/policies})</li>
 *   <li>Per-user usage statistics ({@code GET /gateway/admin/usage/{userId}})</li>
 *   <li>Manual policy refresh trigger ({@code POST /gateway/admin/refresh})</li>
 * </ul>
 */
@RestController
@RequestMapping("/gateway")
public class GatewayInfoController {

    private static final String GATEWAY_VERSION = "1.0.0-SNAPSHOT (Phase 1)";

    /**
     * Returns basic gateway info.
     *
     * <p>Sample response:</p>
     * <pre>{@code
     * {
     *   "service"   : "API Gateway",
     *   "version"   : "1.0.0-SNAPSHOT (Phase 1)",
     *   "status"    : "UP",
     *   "timestamp" : "2025-01-01T00:00:00Z"
     * }
     * }</pre>
     */
    @GetMapping("/info")
    public Mono<Map<String, String>> info() {
        return Mono.just(Map.of(
                "service", "API Gateway",
                "version", GATEWAY_VERSION,
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
