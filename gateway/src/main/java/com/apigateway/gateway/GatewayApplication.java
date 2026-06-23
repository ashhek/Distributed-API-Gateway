package com.apigateway.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Entry point for the API Gateway application.
 *
 * <p>Spring Cloud Gateway runs on Netty (non-blocking, reactive I/O) which is
 * why we use WebFlux throughout — do NOT mix in servlet-based components.</p>
 *
 * <p>Responsibilities of this module (cumulative):</p>
 * <ul>
 *   <li><b>Phase 1:</b> Route incoming requests to downstream microservices.</li>
 *   <li><b>Phase 2:</b> Validate JWT tokens via a global pre-filter; inject
 *       {@code X-User-Id} and {@code X-User-Role} headers for downstream services.</li>
 *   <li><b>Phase 3:</b> Enforce per-user / per-route rate limits via Redis Token Bucket.</li>
 *   <li><b>Phase 4:</b> Refresh rate-limit policies dynamically from Config Server.</li>
 * </ul>
 *
 * <p>{@link ConfigurationPropertiesScan} auto-discovers all
 * {@code @ConfigurationProperties} beans (e.g., {@code JwtProperties},
 * future {@code RateLimitProperties}) without needing
 * {@code @EnableConfigurationProperties} on every config class.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
