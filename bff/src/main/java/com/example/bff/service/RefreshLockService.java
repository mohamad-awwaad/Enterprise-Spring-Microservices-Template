package com.example.bff.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Per-session (per-{@code jti}) refresh lock, backed by a single Redis key with an expiry
 * ({@code SET bff:session:<jti>:refresh-lock NX PX ...}).
 * <p>
 * {@link com.example.bff.filter.TokenRefreshFilter} uses this to make sure only one request -
 * across every BFF instance, not just this JVM - actually calls Keycloak's token endpoint to
 * refresh a given session's tokens at a time. This matters once refresh token rotation is
 * enabled in the realm ({@code revokeRefreshToken=true}, {@code refreshTokenMaxReuse=0}): a
 * refresh token can only be redeemed once, so two concurrent requests racing to refresh the same
 * expiring session would otherwise have their second request rejected with {@code invalid_grant}
 * (and, worse, could otherwise race to store stale tokens over fresh ones).
 * <p>
 * The lock key is separate from the session's own {@code bff:session:<jti>} key (it carries no
 * session data, just a marker) and expires on its own after {@link #LOCK_TTL} even if the holder
 * crashes mid-refresh, so a dead node can never wedge a session's refresh forever.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshLockService {

    private static final String LOCK_KEY_PREFIX = "bff:session:";
    private static final String LOCK_KEY_SUFFIX = ":refresh-lock";

    /** Marker value written to the lock key; its content is never read back, only its presence. */
    private static final String LOCK_VALUE = "1";

    /**
     * Safety-net expiry: long enough to cover a real (slow) Keycloak round trip, short enough
     * that a crashed holder never blocks other nodes for more than a few seconds.
     */
    private static final Duration LOCK_TTL = Duration.ofMillis(5000);

    /** How often a non-holder re-checks whether the lock has been released. */
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    /** Longest a non-holder will wait before giving up and moving on regardless. */
    private static final Duration MAX_WAIT = Duration.ofMillis(3000);

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Attempts to become the single holder of the refresh lock for {@code jti}.
     *
     * @return {@code true} if this call acquired the lock (caller must refresh, then
     *         {@link #release(String)}); {@code false} if someone else already holds it.
     */
    public boolean tryAcquire(String jti) {
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey(jti), LOCK_VALUE, LOCK_TTL);
        return Boolean.TRUE.equals(acquired);
    }

    /** Releases the lock. Only the holder (the caller whose {@link #tryAcquire} returned true) should call this. */
    public void release(String jti) {
        stringRedisTemplate.delete(lockKey(jti));
    }

    /**
     * Blocks the calling thread, polling every {@value #POLL_INTERVAL}ms, until the lock for
     * {@code jti} disappears (the holder finished and released it, or its TTL expired) or
     * {@value #MAX_WAIT}ms have passed, whichever comes first. Never throws for a timeout - the
     * caller simply proceeds afterwards and reloads the session to see whatever state the holder
     * (or nobody) left behind.
     */
    public void waitForRelease(String jti) {
        String key = lockKey(jti);
        long deadline = System.currentTimeMillis() + MAX_WAIT.toMillis();

        while (System.currentTimeMillis() < deadline) {
            if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
                return;
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("Timed out waiting for concurrent token refresh lock on jti={} to release", jti);
    }

    private static String lockKey(String jti) {
        return LOCK_KEY_PREFIX + jti + LOCK_KEY_SUFFIX;
    }
}
