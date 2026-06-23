package com.apigateway.configserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.config.server.EnableConfigServer;

/**
 * <h2>ConfigServerApplication</h2>
 *
 * <p>Spring Cloud Config Server — serves configuration files to all client
 * microservices (gateway, downstream-service) over HTTP.</p>
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Config clients (e.g., the gateway) bootstrap by calling:
 *       {@code GET http://localhost:8888/api-gateway/default}</li>
 *   <li>The server resolves the config file from the configured back-end
 *       (filesystem in dev, Git in production).</li>
 *   <li>Clients merge the remote config with their local {@code application.yml}.</li>
 *   <li>When a policy changes, POST {@code /actuator/refresh} on the gateway
 *       to hot-reload without a restart.</li>
 * </ol>
 *
 * <h3>Phase 4 integration</h3>
 * <p>In Phase 4 we will place rate-limit tier definitions
 * (capacity, refill-rate per tier) in the config repo and have the gateway
 * fetch and cache them with {@code @RefreshScope}.</p>
 */
@SpringBootApplication
@EnableConfigServer
public class ConfigServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ConfigServerApplication.class, args);
    }
}
