package com.aicompany.backend.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need the application running against a real
 * PostgreSQL database. Sharing one annotation set lets Spring reuse a single
 * application context, and therefore a single container, across subclasses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestcontainerConfig.class)
public abstract class AbstractPostgresTest {
}
