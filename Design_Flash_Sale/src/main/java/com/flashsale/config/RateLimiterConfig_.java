package com.flashsale.config;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.time.Duration;

/**
 * Rate Limiter Configuration.
 *
 * Implements a per-user rate limiter using Resilience4j.
 * In production, this would use Redis sliding window counters
 * for distributed rate limiting across all instances.
 *
 * Configuration:
 *   - Purchase endpoint: 5 requests per second per user
 *   - Global: 10,000 requests per second
 */
@Configuration
@Slf4j
public class RateLimiterConfig_ {

    /**
     * Rate limiter for the purchase endpoint.
     * Token bucket: 5 requests/second refill, burst capacity 10.
     */
    @Bean
    public RateLimiterRegistry rateLimiterRegistry() {
        RateLimiterConfig config = RateLimiterConfig.custom()
                .limitRefreshPeriod(Duration.ofSeconds(1))  // refill every second
                .limitForPeriod(5)                          // 5 tokens per period
                .timeoutDuration(Duration.ofMillis(500))    // wait up to 500ms
                .build();

        return RateLimiterRegistry.of(config);
    }

    /**
     * Servlet filter that intercepts purchase requests and applies rate limiting.
     *
     * In production, replace this with a Redis-based sliding window:
     *   Key:   rate_limit:user:{userId}:purchase:{timestamp_second}
     *   Cmd:   INCR + EXPIRE
     *   Check: if count > limit → reject with 429
     */
    @Bean
    @Order(1)
    public Filter rateLimiterFilter(RateLimiterRegistry registry) {
        return new Filter() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
                    throws IOException, ServletException {

                HttpServletRequest httpReq = (HttpServletRequest) req;
                HttpServletResponse httpRes = (HttpServletResponse) res;

                // Only rate-limit purchase endpoints
                String path = httpReq.getRequestURI();
                if (path.contains("/purchase") && "POST".equalsIgnoreCase(httpReq.getMethod())) {

                    String userId = httpReq.getHeader("X-User-Id");
                    if (userId == null) userId = "anonymous";

                    // Get or create per-user rate limiter
                    RateLimiter limiter = registry.rateLimiter("purchase-" + userId);

                    if (!limiter.acquirePermission()) {
                        log.warn("Rate limited: user={}, path={}", userId, path);
                        httpRes.setStatus(429);
                        httpRes.setContentType("application/json");
                        httpRes.getWriter().write(
                            "{\"error\": \"RATE_LIMITED\", \"message\": \"Too many requests. Please wait.\", \"retryAfter\": 2}");
                        return;
                    }
                }

                chain.doFilter(req, res);
            }
        };
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
