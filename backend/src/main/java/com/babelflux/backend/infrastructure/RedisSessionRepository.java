package com.babelflux.backend.infrastructure;

import com.babelflux.backend.domain.Session;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Redis adapter reserved for multi-instance deployments; domain code depends on SessionRepository. */
@Component
@ConditionalOnProperty(prefix = "babelflux.infrastructure", name = "redis-enabled", havingValue = "true")
public class RedisSessionRepository {
    private final StringRedisTemplate redis;

    public RedisSessionRepository(StringRedisTemplate redis) { this.redis = redis; }

    public void markRunning(String sessionId) {
        redis.opsForValue().set("babelflux:session:" + sessionId, "running", Duration.ofHours(24));
    }
}
