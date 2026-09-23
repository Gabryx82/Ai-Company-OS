package com.aicompany.backend.support;

import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need the application running against a real
 * PostgreSQL database. Sharing one annotation set lets Spring reuse a single
 * application context, and therefore a single container, across subclasses.
 *
 * <p>The shared {@code MockMvc} is authenticated as the operator (TASK-013):
 * see {@link AuthenticatedMockMvcConfiguration}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({PostgresTestcontainerConfig.class, AuthenticatedMockMvcConfiguration.class})
public abstract class AbstractPostgresTest {
}
