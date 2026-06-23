package com.apigateway.downstream.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

/**
 * <h2>DataController</h2>
 *
 * <p>Simulates a real backend microservice. In Phase 2, the gateway's
 * {@code JwtAuthenticationFilter} strips the raw {@code Authorization} header
 * and injects {@code X-User-Id} and {@code X-User-Role} instead, so this
 * service never sees raw JWTs — it only receives pre-validated identity claims.</p>
 *
 * <ul>
 *   <li>{@code GET  /api/data}       – All records + authenticated user info</li>
 *   <li>{@code GET  /api/data/{id}}  – Single record by ID</li>
 *   <li>{@code POST /api/data}       – Create (simulated)</li>
 *   <li>{@code GET  /api/echo/**}    – Debug: mirrors all received headers</li>
 *   <li>{@code GET  /api/health}     – Liveness probe</li>
 * </ul>
 */
@Slf4j
@RestController
public class DataController {

    // ── Static mock dataset ───────────────────────────────────────────────────
    private static final List<Map<String, Object>> MOCK_DATA = List.of(
            Map.of("id", 1, "name", "Product Alpha",    "category", "Electronics", "price", 299.99, "stock", 150),
            Map.of("id", 2, "name", "Product Beta",     "category", "Clothing",    "price",  49.99, "stock", 320),
            Map.of("id", 3, "name", "Product Gamma",    "category", "Books",       "price",  19.99, "stock", 800),
            Map.of("id", 4, "name", "Product Delta",    "category", "Electronics", "price", 599.99, "stock",  75),
            Map.of("id", 5, "name", "Product Epsilon",  "category", "Food",        "price",   9.99, "stock", 500)
    );

    // ── GET /api/data ─────────────────────────────────────────────────────────

    /**
     * Returns all mock records.
     * Phase 2: surfaces the {@code X-User-Id} and {@code X-User-Role} headers
     * that the gateway JWT filter injects, proving identity propagation works.
     */
    @GetMapping("/api/data")
    public ResponseEntity<Map<String, Object>> getAllData(
            @RequestHeader(value = "X-Gateway-Source",   required = false) String gatewaySource,
            @RequestHeader(value = "X-Correlation-Id",   required = false) String correlationId,
            @RequestHeader(value = "X-Gateway-Request-Id", required = false) String requestId,
            @RequestHeader(value = "X-User-Id",   required = false) String userId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole) {

        log.info("GET /api/data | correlationId={} | userId={} | role={}",
                correlationId, userId, userRole);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("timestamp", Instant.now().toString());
        response.put("totalRecords", MOCK_DATA.size());
        response.put("data", MOCK_DATA);
        response.put("meta", Map.of(
                "routedViaGateway", gatewaySource != null,
                "gatewaySource",    Objects.requireNonNullElse(gatewaySource, "direct"),
                "correlationId",    Objects.requireNonNullElse(correlationId, "none"),
                "requestId",        Objects.requireNonNullElse(requestId, "none"),
                "authenticatedUserId",  Objects.requireNonNullElse(userId, "anonymous"),
                "authenticatedRole",    Objects.requireNonNullElse(userRole, "none")
        ));

        return ResponseEntity.ok(response);
    }

    // ── GET /api/data/{id} ────────────────────────────────────────────────────

    /**
     * Returns a single mock record by integer ID.
     * Returns 404 if the ID is not found in the mock dataset.
     */
    @GetMapping("/api/data/{id}")
    public ResponseEntity<Map<String, Object>> getDataById(
            @PathVariable Integer id,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        log.info("GET /api/data/{} | correlationId={}", id, correlationId);

        return MOCK_DATA.stream()
                .filter(record -> id.equals(record.get("id")))
                .findFirst()
                .map(record -> {
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("status", "success");
                    response.put("timestamp", Instant.now().toString());
                    response.put("data", record);
                    return ResponseEntity.ok(response);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(Map.of(
                        "status", "error",
                        "message", "Record with id=" + id + " not found",
                        "timestamp", Instant.now().toString()
                )));
    }

    // ── POST /api/data ────────────────────────────────────────────────────────

    /**
     * Accepts a JSON payload and echoes it back with an assigned mock ID.
     * Simulates a write operation on the downstream service.
     */
    @PostMapping("/api/data")
    public ResponseEntity<Map<String, Object>> createData(
            @RequestBody Map<String, Object> payload,
            @RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {

        log.info("POST /api/data | correlationId={} | payload={}", correlationId, payload);

        // Simulate assigned ID
        int assignedId = new Random().nextInt(10_000) + 100;

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "created");
        response.put("timestamp", Instant.now().toString());
        response.put("assignedId", assignedId);
        response.put("receivedPayload", payload);
        response.put("message", "Record created successfully (simulated)");

        return ResponseEntity.status(201).body(response);
    }

    // ── GET /api/echo/** ──────────────────────────────────────────────────────

    /**
     * Debug endpoint: reflects all request headers and metadata back to the caller.
     * Extremely useful in Phase 1 to verify what headers the gateway injects.
     */
    @GetMapping("/api/echo/**")
    public ResponseEntity<Map<String, Object>> echo(HttpServletRequest request) {
        log.info("GET /api/echo | remoteAddr={}", request.getRemoteAddr());

        Map<String, List<String>> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames()).forEach(name ->
                headers.put(name, Collections.list(request.getHeaders(name))));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "success");
        response.put("timestamp", Instant.now().toString());
        response.put("method", request.getMethod());
        response.put("requestUri", request.getRequestURI());
        response.put("remoteAddr", request.getRemoteAddr());
        response.put("headers", headers);

        return ResponseEntity.ok(response);
    }

    // ── GET /api/health ───────────────────────────────────────────────────────

    /**
     * Simple liveness probe endpoint.
     * The gateway routes {@code /api/health/**} here for infrastructure health checks.
     */
    @GetMapping("/api/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "downstream-service",
                "timestamp", Instant.now().toString()
        ));
    }
}
