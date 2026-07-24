package com.family.scrollshield;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;

/**
 * Base for integration tests. Boots the full Spring context against real PostgreSQL and
 * Redis containers (Testcontainers), and replaces the production system clock with a
 * {@link ControllableClock} so tests can drive DST, cross-midnight and expiry precisely.
 *
 * <p>PostgreSQL is the authority under test; Redis is the lossable accelerator. The
 * cache-loss tests flush Redis mid-flight and assert correctness is preserved.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                    .withDatabaseName("scrollshield")
                    .withUsername("scrollshield")
                    .withPassword("scrollshield");

    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /** Shared, test-controllable clock installed as the primary Clock bean. */
    public static final ControllableClock CLOCK =
            new ControllableClock(Instant.parse("2026-01-15T12:00:00Z"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        // Keep background reaper/publisher quiet unless a test explicitly invokes them.
        registry.add("scrollshield.lease.reaper-interval-ms", () -> "3600000");
        registry.add("scrollshield.outbox.publish-interval-ms", () -> "3600000");
    }
}
