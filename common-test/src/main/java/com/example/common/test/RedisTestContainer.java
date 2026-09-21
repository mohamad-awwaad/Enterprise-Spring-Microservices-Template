package com.example.common.test;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * A real Redis server for hermetic integration tests that need Redis (BFF sessions, the
 * Gateway's rate limiter) without depending on the Redis instance in the local dev
 * {@code docker compose} stack.
 * <p>
 * Started once per JVM as a singleton, mirroring {@link KeycloakTestContainer}: shared across
 * every test class that calls {@link #getInstance()}, and torn down by Testcontainers' Ryuk
 * reaper on JVM exit rather than explicitly.
 */
public final class RedisTestContainer extends GenericContainer<RedisTestContainer> {

    private static final String IMAGE = "redis:8-alpine";
    private static final int REDIS_PORT = 6379;

    private static volatile RedisTestContainer instance;

    private RedisTestContainer() {
        super(IMAGE);
        withExposedPorts(REDIS_PORT);
        waitingFor(Wait.forLogMessage(".*Ready to accept connections.*", 1));
    }

    /**
     * Returns the shared, already-started container, starting it on first use.
     */
    public static RedisTestContainer getInstance() {
        RedisTestContainer result = instance;
        if (result == null) {
            synchronized (RedisTestContainer.class) {
                result = instance;
                if (result == null) {
                    result = new RedisTestContainer();
                    result.start();
                    instance = result;
                }
            }
        }
        return result;
    }

    public String host() {
        return getHost();
    }

    public int port() {
        return getMappedPort(REDIS_PORT);
    }
}
