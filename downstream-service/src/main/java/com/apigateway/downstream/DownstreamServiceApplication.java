package com.apigateway.downstream;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Downstream Microservice.
 *
 * <p>This is a deliberately simple Spring Boot MVC (servlet-based) application
 * that simulates a real backend microservice. In a production system this would
 * be replaced by any number of actual domain services (user-service, order-service, etc.)
 * all sitting behind the gateway.</p>
 *
 * <p><strong>Design note:</strong> The downstream service should never be exposed
 * directly to the internet — only the gateway's port (8080) should be public-facing.
 * Port 8081 should be bound to an internal/private network interface only.</p>
 */
@SpringBootApplication
public class DownstreamServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(DownstreamServiceApplication.class, args);
    }
}
