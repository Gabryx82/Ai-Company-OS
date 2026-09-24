package com.aicompany.backend.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
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
@Import({PostgresTestcontainerConfig.class, AuthenticatedMockMvcConfiguration.class,
        ScriptedEngineConfiguration.class, FakeHostConfiguration.class})
public abstract class AbstractPostgresTest {

    @Autowired
    private JdbcTemplate baseJdbc;

    @Autowired
    private ScriptedEngineClient baseEngine;

    /**
     * Runs reference tasks and agents, and every test class that clears those
     * tables with {@code deleteAll()} would otherwise trip on a run another class
     * left behind. Base-class {@code @BeforeEach} runs before the subclass's, so
     * the runs are gone before anybody deletes what they point at (TASK-019).
     */
    @Autowired
    private FakeHost baseHost;

    @BeforeEach
    void clearRunsAndTheScriptedEngine() {
        // PHASE 13: daily items reference tasks that other tests delete.
        baseJdbc.update("DELETE FROM daily_items");
        // PHASE 11: reviews and handoffs reference runs and tasks; they go first.
        baseJdbc.update("DELETE FROM task_reviews");
        baseJdbc.update("DELETE FROM task_handoffs");
        baseJdbc.update("DELETE FROM task_runs");
        // PHASE 10: plans reference projects, and tests of other domains delete
        // projects in their own setup. Detach and remove every plan first.
        baseJdbc.update("UPDATE tasks SET phase_id = NULL WHERE phase_id IS NOT NULL");
        baseJdbc.update("DELETE FROM project_phases");
        baseJdbc.update("DELETE FROM plan_runs");
        // PHASE 12: links reference projects and agents that other tests delete.
        baseJdbc.update("DELETE FROM project_resources");
        baseJdbc.update("DELETE FROM agent_resources");
        baseJdbc.update("DELETE FROM agent_software");
        baseJdbc.update("UPDATE agents SET parent_id = NULL WHERE parent_id IS NOT NULL");
        baseEngine.reset();
        baseHost.reset();
    }
}
