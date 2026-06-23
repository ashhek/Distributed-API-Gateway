package com.apigateway.gateway.controller;

import com.apigateway.gateway.ratelimit.RateLimitProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final MeterRegistry meterRegistry;
    private final RateLimitProperties rateLimitProperties;
    private final WebClient webClient;

    public AdminController(MeterRegistry meterRegistry, 
                           RateLimitProperties rateLimitProperties, 
                           WebClient.Builder webClientBuilder) {
        this.meterRegistry = meterRegistry;
        this.rateLimitProperties = rateLimitProperties;
        this.webClient = webClientBuilder.build();
    }

    @GetMapping("/metrics")
    public Mono<Map<String, Double>> getMetrics() {
        double allowed = 0;
        double rejected = 0;
        try {
            allowed = meterRegistry.get("gateway.ratelimit.requests").tag("status", "allowed").counter().count();
        } catch (MeterNotFoundException e) {
            // metric not created yet (no requests)
        }
        try {
            rejected = meterRegistry.get("gateway.ratelimit.requests").tag("status", "rejected").counter().count();
        } catch (MeterNotFoundException e) {
            // metric not created yet
        }
        return Mono.just(Map.of("allowed", allowed, "rejected", rejected));
    }

    @GetMapping("/policies")
    public Mono<Map<String, Object>> getPolicies() {
        return Mono.just(Map.of(
            "FREE", rateLimitProperties.getTiers().get("FREE"),
            "PRO", rateLimitProperties.getTiers().get("PRO")
        ));
    }

    @PostMapping("/policies")
    public Mono<ResponseEntity<String>> updatePolicies(@RequestBody Map<String, Map<String, Integer>> payload) {
        try {
            // We map the local config-repo directory into the container at /config-repo
            Path configPath = Paths.get("/config-repo/api-gateway.yml");
            if (!Files.exists(configPath)) {
                // Fallback for local development if volume isn't mapped, edit the original file directly
                configPath = Paths.get("config-server/src/main/resources/config-repo/api-gateway.yml");
            }
            if (!Files.exists(configPath)) {
                return Mono.just(ResponseEntity.status(500).body("Config file not found."));
            }

            List<String> lines = Files.readAllLines(configPath);
            boolean inFree = false;
            boolean inPro = false;
            
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.matches("\\s+FREE:\\s*")) { inFree = true; inPro = false; }
                else if (line.matches("\\s+PRO:\\s*")) { inPro = true; inFree = false; }
                else if (line.matches("\\s+[A-Z]+:\\s*")) { inFree = false; inPro = false; }
                
                if (inFree && payload.containsKey("FREE")) {
                    if (line.matches("\\s+replenish-rate:.*") && payload.get("FREE").get("replenishRate") != null) {
                        lines.set(i, line.replaceAll("replenish-rate:.*", "replenish-rate: " + payload.get("FREE").get("replenishRate")));
                    }
                    if (line.matches("\\s+burst-capacity:.*") && payload.get("FREE").get("burstCapacity") != null) {
                        lines.set(i, line.replaceAll("burst-capacity:.*", "burst-capacity: " + payload.get("FREE").get("burstCapacity")));
                    }
                }
                if (inPro && payload.containsKey("PRO")) {
                    if (line.matches("\\s+replenish-rate:.*") && payload.get("PRO").get("replenishRate") != null) {
                        lines.set(i, line.replaceAll("replenish-rate:.*", "replenish-rate: " + payload.get("PRO").get("replenishRate")));
                    }
                    if (line.matches("\\s+burst-capacity:.*") && payload.get("PRO").get("burstCapacity") != null) {
                        lines.set(i, line.replaceAll("burst-capacity:.*", "burst-capacity: " + payload.get("PRO").get("burstCapacity")));
                    }
                }
            }
            
            Files.write(configPath, lines);

            // Trigger the internal actuator refresh
            return webClient.post()
                    .uri("http://localhost:8080/actuator/refresh")
                    .retrieve()
                    .bodyToMono(String.class)
                    .map(res -> ResponseEntity.ok("{\"status\":\"success\",\"message\":\"Policies updated.\"}"));

        } catch (Exception e) {
            e.printStackTrace();
            return Mono.just(ResponseEntity.status(500).body("{\"status\":\"error\",\"message\":\"" + e.getMessage() + "\"}"));
        }
    }
}
