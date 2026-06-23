package com.apigateway.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

/**
 * <h2>RedisConfig</h2>
 *
 * <p>Configures the {@link ReactiveStringRedisTemplate} bean used by both
 * Spring Cloud Gateway's built-in rate limiter infrastructure
 * ({@code GatewayRedisAutoConfiguration}) and our custom Token Bucket
 * filter (Phase 3).</p>
 *
 * <h3>Why ReactiveStringRedisTemplate?</h3>
 * <p>Spring Cloud Gateway's {@code GatewayRedisAutoConfiguration} declares an
 * {@code @Autowired} parameter of type {@code ReactiveStringRedisTemplate}.
 * Using the plain {@code ReactiveRedisTemplate<String, String>} does not satisfy
 * this injection point. {@link ReactiveStringRedisTemplate} is a Spring-provided
 * subclass pre-configured with {@code StringRedisSerializer} for all
 * key/value operations that satisfies the exact type check.</p>
 *
 * <h3>Connection</h3>
 * <p>Spring Boot auto-configures a {@code LettuceConnectionFactory} from
 * {@code spring.data.redis.*} properties in {@code application.yml}. We inject
 * that factory here so there is a single, shared connection pool.</p>
 *
 * <h3>Production Note</h3>
 * <p>For Redis Cluster or Sentinel deployments, update
 * {@code spring.data.redis.cluster.*} or {@code spring.data.redis.sentinel.*}
 * in {@code application.yml} — no code changes required here.</p>
 */
@Configuration
public class RedisConfig {

    /**
     * Creates a {@link ReactiveStringRedisTemplate} backed by the auto-configured
     * Lettuce connection factory.
     *
     * <p>Marked {@code @Primary} so it is selected over any other
     * {@code ReactiveStringRedisTemplate} that may be contributed by auto-configuration.</p>
     */
    @Bean
    @Primary
    public ReactiveStringRedisTemplate reactiveStringRedisTemplate(
            ReactiveRedisConnectionFactory factory) {
        return new ReactiveStringRedisTemplate(factory);
    }
}
