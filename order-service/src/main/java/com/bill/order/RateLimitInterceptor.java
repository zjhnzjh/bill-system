package com.bill.order;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate redis;
    private final int requestsPerSecond;

    public RateLimitInterceptor(StringRedisTemplate redis,
                                @Value("${reliability.rate-limit-per-second:50}") int requestsPerSecond) {
        this.redis = redis;
        this.requestsPerSecond = requestsPerSecond;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String client = request.getHeader("X-Client-Id");
        if (client == null || client.isBlank()) client = request.getRemoteAddr();
        long second = Instant.now().getEpochSecond();
        String key = "bill:rate:create-order:" + client + ":" + second;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) redis.expire(key, Duration.ofSeconds(2));

        response.setHeader("X-RateLimit-Limit", String.valueOf(requestsPerSecond));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, requestsPerSecond - (count == null ? 0 : count))));
        if (count != null && count > requestsPerSecond) {
            response.setStatus(429);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write("{\"code\":\"RATE_LIMITED\",\"message\":\"Too many create-order requests; retry in the next one-second window\"}");
            return false;
        }
        return true;
    }
}
