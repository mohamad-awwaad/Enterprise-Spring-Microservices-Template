package com.example.bff.service;

import com.example.bff.session.BffSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed store for {@link BffSession} records, keyed by the {@code jti} issued in the
 * BFF_SESSION cookie's signed JWT.
 */
@Service
@RequiredArgsConstructor
public class SessionRedisService {

    /**
     * Every session is stored under this prefix (rather than the bare {@code jti}) so Redis
     * ACLs, key-space monitoring, and {@code SCAN} patterns can target BFF sessions specifically
     * instead of the whole keyspace.
     */
    private static final String KEY_PREFIX = "bff:session:";

    private final RedisTemplate<String, BffSession> redisTemplate;

    @Value("${bff.session.ttl-minutes}")
    private int sessionTtlMinutes;

    public void save(String jti, BffSession session) {
        redisTemplate.opsForValue().set(KEY_PREFIX + jti, session, Duration.ofMinutes(sessionTtlMinutes));
    }

    public BffSession load(String jti) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + jti);
    }

    public void delete(String jti) {
        redisTemplate.delete(KEY_PREFIX + jti);
    }
}
