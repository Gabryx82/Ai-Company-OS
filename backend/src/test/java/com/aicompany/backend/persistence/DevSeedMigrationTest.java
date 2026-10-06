package com.aicompany.backend.persistence;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.support.PostgresTestcontainerConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The development seed has a single owner — a versioned migration under
 * {@code db/dev} — applied only under the "dev" profile, and it lives in a Flyway
 * stream of its own.
 *
 * <p>This test runs the real application context. {@link MigrationStreamTest}
 * covers the stream mechanics on databases in states a context cannot express.
 */
// PHASE 15: the dev profile reads the operator's ~/.aicos (secrets file, first-admin
// password). A test must never read or write the developer's own home: it gets a
// temporary one, and an admin password of its own.
@SpringBootTest(properties = {
        "AICOS_HOME=${java.io.tmpdir}/aicos-devseed-test-home",
        "aicos.home=${java.io.tmpdir}/aicos-devseed-test-home",
        "aicos.security.admin.password=devseed-test-secret-42",
        // PHASE 21: a test never starts programs on the developer's machine.
        "aicos.ecosystem.autostart=false",
        "aicos.launcher.enabled=false"})
@ActiveProfiles("dev")
@Import(PostgresTestcontainerConfig.class)
class DevSeedMigrationTest {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.aicompany.backend.binding.AgentConfigurationService configurations;

    @Test
    void devProfileSeedsTheDemonstrationAgents() {

        assertThat(agentRepository.findAll())
                .hasSize(3)
                .extracting("name")
                .containsExactlyInAnyOrder("Code Architect", "Frontend Developer", "Database Specialist");
    }

    /**
     * PHASE 16 (ADR-025): the seed agents are marked SEED, carry the configuration
     * they were born with, and are bound to verified local models on the AI Engine.
     * PHASE 29 (ADR-034): Qwen 3.5 9B for all three -- the coders left DeepSeek Coder
     * V2, which it outscored on the same machine -- and the baseline moved with them,
     * so nothing reads as modified.
     */
    @Test
    void theSeedAgentsHaveAVisibleInitialBindingAndNothingModifiedYet() {
        Map<String, String> models = new java.util.HashMap<>();
        for (var agent : agentRepository.findAll()) {
            assertThat(agent.getOrigin()).as(agent.getName()).isEqualTo(com.aicompany.backend.agent.model.AgentOrigin.SEED);
            assertThat(agent.getExecutionTarget()).isEqualTo("engine");
            assertThat(agent.getBaseline()).contains("\"model\":\"" + agent.getModel() + "\"");
            assertThat(agent.getSystemPrompt()).isNotBlank();
            assertThat(configurations.of(agent.getId()).modified()).as(agent.getName()).isEmpty();
            models.put(agent.getName(), agent.getModel());
        }
        assertThat(models).containsEntry("Code Architect", "ollama:qwen3.5:9b")
                .containsEntry("Frontend Developer", "ollama:qwen3.5:9b")
                .containsEntry("Database Specialist", "ollama:qwen3.5:9b");
    }

    @Autowired
    private com.aicompany.backend.harness.service.AgentTemplates templates;

    /**
     * PHASE 30: the three seed agents are also templates, with the configuration the
     * seed gives them -- so a reinstalled Code Architect is the Code Architect the seed
     * created, and «Da template» sees the seeded ones as already there, never twice.
     */
    @Test
    void theSeedAgentsAreAlsoTemplatesWithTheSameConfiguration() {
        for (var agent : agentRepository.findAll()) {
            var template = templates.all().stream().filter(t -> t.name().equals(agent.getName())).findFirst();
            assertThat(template).as(agent.getName() + " is a template").isPresent();
            var t = template.get();
            assertThat(t.role()).isEqualTo(agent.getRole());
            assertThat(t.specialization()).isEqualTo(agent.getSpecialization());
            assertThat(t.model()).isEqualTo(agent.getModel());
            assertThat(t.executionTarget()).isEqualTo(agent.getExecutionTarget());
            assertThat(t.description()).isEqualTo(agent.getDescription());
            assertThat(String.join("\n", t.capabilities())).isEqualTo(agent.getCapabilities());
            assertThat(t.systemPrompt()).isEqualTo(agent.getSystemPrompt());
            assertThat(t.responsibilities()).isEqualTo(agent.getResponsibilities());
            assertThat(String.join("\n", t.directives())).isEqualTo(agent.getDirectives());
        }
        assertThat(templates.plan(null)).filteredOn(p -> java.util.Set.of("Code Architect", "Frontend Developer",
                        "Database Specialist").contains(p.name()))
                .hasSize(3)
                .allMatch(p -> p.outcome() == com.aicompany.backend.harness.service.AgentTemplates.Outcome.EXISTING);
    }

    @Test
    void theSeedIsRecordedInItsOwnHistoryNotInTheSchemaHistory() {

        // The schema stream is the same one production runs: schema versions and
        // nothing else. No seed version may ever appear here.
        assertThat(versionsIn(DevSeedFlyway.SCHEMA_HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29");

        // The seed keeps its own history, so it never constrains schema versions.
        // "0" is the baseline row written because the schema stream had already
        // created the tables; "1" is the seed migration; "2" (PHASE 16) gives the
        // seed agents their initial binding and configuration; "3" (PHASE 29) the
        // local model upgrade.
        assertThat(versionsIn(DevSeedFlyway.HISTORY_TABLE)).containsExactly("0", "1", "2", "3");
    }

    @Test
    void runningBothStreamsAgainDoesNotDuplicateTheSeed() {

        long before = agentRepository.count();

        // What a restart does: schema stream first, then the seed stream.
        flyway.migrate();
        var seedRun = DevSeedFlyway.apply(dataSource, null);

        assertThat(seedRun.migrationsExecuted).isZero();
        assertThat(agentRepository.count()).isEqualTo(before).isEqualTo(3);
    }

    private List<String> versionsIn(String historyTable) {
        return jdbc.queryForList(
                "SELECT version FROM " + historyTable
                        + " WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
    }
}
