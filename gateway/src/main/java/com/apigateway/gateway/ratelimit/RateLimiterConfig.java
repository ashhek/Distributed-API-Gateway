package com.apigateway.gateway.ratelimit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;

import java.util.List;

/**
 * <h2>RateLimiterConfig</h2>
 *
 * <p>Registers the {@link DefaultRedisScript} bean that wraps the Lua Token Bucket
 * script. Spring Data Redis caches the SHA1 hash of the script after the first
 * {@code EVAL} call, switching automatically to {@code EVALSHA} on subsequent
 * calls — a significant performance gain on high-throughput paths.</p>
 *
 * <h3>How script loading works</h3>
 * <ol>
 *   <li>On startup, {@link DefaultRedisScript} reads the {@code .lua} file from
 *       the classpath and stores its source text.</li>
 *   <li>On first execution, Redis evaluates it with {@code EVAL}, computes the
 *       SHA1, and caches it in the script cache.</li>
 *   <li>On every subsequent execution, Spring Data Redis uses {@code EVALSHA}
 *       with the cached SHA1 — the script bytes are never re-sent over the wire.</li>
 * </ol>
 *
 * <h3>Return type</h3>
 * <p>The Lua script returns a three-element array which Redis maps to
 * {@code List<Long>}:</p>
 * <ul>
 *   <li>{@code [0]} — {@code 1} (allowed) or {@code 0} (rejected)</li>
 *   <li>{@code [1]} — remaining tokens (floor of fractional value)</li>
 *   <li>{@code [2]} — burst capacity (for {@code X-RateLimit-Limit} header)</li>
 * </ul>
 */
@Configuration
public class RateLimiterConfig {

    /** Classpath location of the Lua script (under src/main/resources). */
    private static final String LUA_SCRIPT_PATH = "scripts/request_rate_limiter.lua";

    /**
     * Loads and registers the Token Bucket Lua script as a Spring-managed bean.
     *
     * <p>Using {@link ResourceScriptSource} means the script is loaded lazily
     * from the classpath on first use. In production, consider calling
     * {@link DefaultRedisScript#afterPropertiesSet()} explicitly during startup
     * to validate the script at boot time rather than on the first request.</p>
     *
     * @return a configured {@link DefaultRedisScript} ready for reactive execution
     */
    @Bean
    @SuppressWarnings("unchecked")
    public DefaultRedisScript<List<Long>> rateLimiterScript() {
        DefaultRedisScript<List<Long>> script = new DefaultRedisScript<>();
        script.setScriptSource(
                new ResourceScriptSource(new ClassPathResource(LUA_SCRIPT_PATH))
        );
        // Tell Spring Data Redis the Lua return type so it uses the correct
        // RedisSerializer when deserializing the response.
        script.setResultType((Class<List<Long>>) (Class<?>) List.class);
        return script;
    }
}
