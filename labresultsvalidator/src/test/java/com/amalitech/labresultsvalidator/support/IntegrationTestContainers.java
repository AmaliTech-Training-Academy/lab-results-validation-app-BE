package com.amalitech.labresultsvalidator.support;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The two containers every integration test shares.
 *
 * <p>They are started once for the whole suite and never stopped — Ryuk reaps them when the JVM
 * exits. Declaring them as singletons rather than per-class {@code @Container} fields means the
 * Spring context is cached across test classes instead of being rebuilt for each, which is the
 * difference between a suite that runs in seconds and one nobody will wait for.
 *
 * <p><strong>Postgres is real on purpose.</strong> The schema is 35 Flyway migrations deep and
 * nothing has ever verified they apply cleanly from an empty database — an in-memory H2 with
 * {@code ddl-auto} would have skipped exactly the thing worth checking. Redis is real because
 * refresh tokens, password-reset tokens and the SSE registry all live there, so auth cannot be
 * exercised without it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class IntegrationTestContainers {

    // Pinned rather than :latest — a suite whose infrastructure drifts under it is not a control.
    private static final DockerImageName POSTGRES_IMAGE =
        DockerImageName.parse("postgres:16-alpine");
    private static final DockerImageName REDIS_IMAGE =
        DockerImageName.parse("redis:7-alpine");

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("validata_test")
            .withReuse(true);
    }

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redisContainer() {
        return new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .withReuse(true);
    }
}
