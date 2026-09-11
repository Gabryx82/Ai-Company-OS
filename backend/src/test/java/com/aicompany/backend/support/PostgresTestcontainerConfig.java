package com.aicompany.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides the real PostgreSQL instance the tests run against.
 *
 * <p>Tests deliberately do not use an embedded database: an in-memory engine
 * would not exercise the PostgreSQL migrations, types or constraints that the
 * application actually depends on.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));
    }
}
