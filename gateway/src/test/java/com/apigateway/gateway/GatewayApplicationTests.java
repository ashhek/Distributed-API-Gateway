package com.apigateway.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * <h2>GatewayApplicationTests</h2>
 *
 * <p>Smoke test that verifies the Spring application context loads successfully.
 * This catches configuration errors (bad YAML, missing beans, circular dependencies)
 * without requiring a running Redis instance.</p>
 *
 * <p>The {@code test} profile disables Redis auto-configuration and uses a mock
 * connection factory so the test can run in a CI environment with no external services.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GatewayApplicationTests {

    @Test
    void contextLoads() {
        // If this test passes, the Spring context assembled correctly.
        // All beans are instantiated, all @Value injections resolved,
        // and all @Configuration classes are valid.
    }
}
