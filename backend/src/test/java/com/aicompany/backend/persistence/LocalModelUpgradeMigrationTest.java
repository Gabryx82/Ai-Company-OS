package com.aicompany.backend.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 29 (ADR-034): the local model upgrade moves only what is still on the old
 * defaults. Seed {@code V3} moves the two seed coders from DeepSeek Coder V2 to
 * Qwen 3.5 9B; schema {@code V27} deprecates DeepSeek in the catalog and records the
 * new gemma4:e4b package. What the operator changed stays as the operator left it.
 *
 * <p>Flyway is driven directly, like {@link MigrationStreamTest}: each case needs a
 * database stopped at a historical version, which an application context cannot express.
 */
@Testcontainers
class LocalModelUpgradeMigrationTest {

    private static final String DEEPSEEK = "ollama:deepseek-coder-v2:16b";
    private static final String QWEN = "ollama:qwen3.5:9b";
    private static final String GEMMA = "ollama:gemma4:e4b";

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Test
    void theSeedCodersMoveToQwenWithTheirBaselineUnlessTheOperatorReboundThem() {
        String schema = "p29_seed";
        schemaFlyway(schema, null).migrate();
        seedFlywayUpTo(schema, "2").migrate();
        // Before the upgrade the operator had bound the Database Specialist to another model.
        jdbc().update("UPDATE \"" + schema + "\".agents SET model = 'ollama:qwen3.5:4b' WHERE name = 'Database Specialist'");
        long frontendVersion = column(schema, "Frontend Developer", "version", Long.class);

        DevSeedFlyway.apply(dataSource(), schema);

        assertThat(column(schema, "Frontend Developer", "model", String.class)).isEqualTo(QWEN);
        assertThat(column(schema, "Frontend Developer", "baseline", String.class))
                .contains("\"model\":\"" + QWEN + "\"").doesNotContain("deepseek");
        assertThat(column(schema, "Frontend Developer", "version", Long.class)).isEqualTo(frontendVersion + 1);
        // The rebound agent keeps its model, and its baseline still says what it was born with.
        assertThat(column(schema, "Database Specialist", "model", String.class)).isEqualTo("ollama:qwen3.5:4b");
        assertThat(column(schema, "Database Specialist", "baseline", String.class)).contains("\"model\":\"" + DEEPSEEK + "\"");
        assertThat(column(schema, "Code Architect", "model", String.class)).isEqualTo(QWEN);

        // Applied once: a restart does not run it again.
        assertThat(DevSeedFlyway.apply(dataSource(), schema).migrationsExecuted).isZero();
    }

    @Test
    void theCatalogDeprecatesDeepSeekAndRecordsTheNewGemmaPackage() {
        String schema = "p29_catalog";
        schemaFlyway(schema, "26").migrate();
        insertOllamaCatalog(schema, "ACTIVE", null, new BigDecimal("9.6"));

        schemaFlyway(schema, null).migrate();

        assertThat(model(schema, DEEPSEEK, "lifecycle")).isEqualTo("DEPRECATED");
        assertThat(model(schema, DEEPSEEK, "replaced_by")).isEqualTo(QWEN);
        assertThat(model(schema, "ollama:gemma4:e4b", "size_gb")).isEqualTo("6.6");
    }

    @Test
    void aCatalogEntryTheOperatorEditedIsLeftAlone() {
        String schema = "p29_catalog_edited";
        schemaFlyway(schema, "26").migrate();
        // The operator already had DeepSeek replaced by a model of their choice, and edited gemma's size.
        insertOllamaCatalog(schema, "ACTIVE", "ollama:qwen3.5:4b", new BigDecimal("7.0"));

        schemaFlyway(schema, null).migrate();

        assertThat(model(schema, DEEPSEEK, "lifecycle")).isEqualTo("ACTIVE");
        assertThat(model(schema, DEEPSEEK, "replaced_by")).isEqualTo("ollama:qwen3.5:4b");
        assertThat(model(schema, "ollama:gemma4:e4b", "size_gb")).isEqualTo("7.0");
    }

    @Test
    void anInstalledTestEngineerFollowsItsTemplateUnlessTheOperatorReboundIt() {
        String untouched = "p29_template";
        schemaFlyway(untouched, "26").migrate();
        insertTemplateTestEngineer(untouched, "ollama:qwen3.5:4b");
        schemaFlyway(untouched, null).migrate();

        assertThat(column(untouched, "Test Engineer", "model", String.class)).isEqualTo("ollama:lfm2.5:8b");
        assertThat(column(untouched, "Test Engineer", "baseline", String.class))
                .contains("\"model\":\"ollama:lfm2.5:8b\"").doesNotContain("qwen3.5:4b");

        String rebound = "p29_template_rebound";
        schemaFlyway(rebound, "26").migrate();
        insertTemplateTestEngineer(rebound, "ollama:qwen3.5:9b");
        schemaFlyway(rebound, null).migrate();

        assertThat(column(rebound, "Test Engineer", "model", String.class)).isEqualTo("ollama:qwen3.5:9b");
        assertThat(column(rebound, "Test Engineer", "baseline", String.class)).contains("\"model\":\"ollama:qwen3.5:4b\"");
    }

    /**
     * PHASE 30 (V29), on the operator's request: the Master Orchestrator and the Learning
     * Coach follow their templates to gemma4:e4b -- with their baseline, so they do not
     * read as modified. One the operator rebound stays; other template agents do not move.
     */
    @Test
    void theOrchestratorAndTheCoachFollowTheirTemplatesToGemmaUnlessTheOperatorReboundThem() {
        String schema = "p30_gemma";
        schemaFlyway(schema, "28").migrate();
        insertTemplateAgent(schema, "Master Orchestrator", QWEN, QWEN);
        insertTemplateAgent(schema, "Learning Coach", "ollama:qwen3.5:4b", QWEN);
        insertTemplateAgent(schema, "Graph Engineer", QWEN, QWEN);
        long orchestratorVersion = column(schema, "Master Orchestrator", "version", Long.class);

        schemaFlyway(schema, null).migrate();

        assertThat(column(schema, "Master Orchestrator", "model", String.class)).isEqualTo(GEMMA);
        assertThat(column(schema, "Master Orchestrator", "baseline", String.class))
                .contains("\"model\":\"" + GEMMA + "\"").doesNotContain("qwen3.5:9b");
        assertThat(column(schema, "Master Orchestrator", "version", Long.class)).isEqualTo(orchestratorVersion + 1);
        assertThat(column(schema, "Learning Coach", "model", String.class)).isEqualTo("ollama:qwen3.5:4b");
        assertThat(column(schema, "Learning Coach", "baseline", String.class)).contains("\"model\":\"" + QWEN + "\"");
        assertThat(column(schema, "Graph Engineer", "model", String.class)).isEqualTo(QWEN);
    }

    /** An agent installed from its template, born on {@code born} and now bound to {@code model}. */
    private void insertTemplateAgent(String schema, String name, String model, String born) {
        jdbc().update("INSERT INTO \"" + schema + "\".agents "
                + "(name, role, specialization, status, model, origin, baseline, created_at, updated_at) "
                + "VALUES (?, ?, 'x', 'ACTIVE', ?, 'TEMPLATE', ?, now(), now())",
                name, name, model, "{\"role\":\"" + name + "\",\"model\":\"" + born + "\"}");
    }

    /** A Test Engineer installed from its template (baseline on qwen3.5:4b), now bound to {@code model}. */
    private void insertTemplateTestEngineer(String schema, String model) {
        jdbc().update("INSERT INTO \"" + schema + "\".agents "
                + "(name, role, specialization, status, model, origin, baseline, created_at, updated_at) "
                + "VALUES ('Test Engineer', 'Test Engineer', 'tests', 'ACTIVE', ?, 'TEMPLATE', ?, now(), now())",
                model, "{\"role\":\"Test Engineer\",\"model\":\"ollama:qwen3.5:4b\"}");
    }

    private void insertOllamaCatalog(String schema, String deepseekLifecycle, String deepseekReplacedBy, BigDecimal gemmaSize) {
        jdbc().update("INSERT INTO \"" + schema + "\".model_providers "
                + "(key, name, kind, engine_provider, billing, status, created_at, updated_at) "
                + "VALUES ('ollama', 'Ollama', 'LOCAL', 'ollama', 'FREE', 'ENABLED', now(), now())");
        jdbc().update("INSERT INTO \"" + schema + "\".llm_models "
                + "(key, provider_key, display_name, role, lifecycle, replaced_by, size_gb, created_at, updated_at) "
                + "VALUES (?, 'ollama', 'DeepSeek Coder V2 16B', 'CODER', ?, ?, 8.9, now(), now())",
                DEEPSEEK, deepseekLifecycle, deepseekReplacedBy);
        jdbc().update("INSERT INTO \"" + schema + "\".llm_models "
                + "(key, provider_key, display_name, role, lifecycle, size_gb, created_at, updated_at) "
                + "VALUES ('ollama:gemma4:e4b', 'ollama', 'Gemma 4 E4B', 'VISION', 'ACTIVE', ?, now(), now())", gemmaSize);
    }

    private String model(String schema, String key, String column) {
        return jdbc().queryForObject("SELECT " + column + "::text FROM \"" + schema + "\".llm_models WHERE key = ?",
                String.class, key);
    }

    private <T> T column(String schema, String agent, String column, Class<T> type) {
        return jdbc().queryForObject("SELECT " + column + " FROM \"" + schema + "\".agents WHERE name = ?", type, agent);
    }

    /** The schema stream, up to {@code target} or to its head when {@code target} is null. */
    private static Flyway schemaFlyway(String schema, String target) {
        var configuration = Flyway.configure().dataSource(dataSource()).locations("classpath:db/migration").schemas(schema);
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        return configuration.load();
    }

    /** The seed stream exactly as the application configures it, stopped at {@code target}. */
    private static Flyway seedFlywayUpTo(String schema, String target) {
        return Flyway.configure()
                .configuration(DevSeedFlyway.flyway(dataSource(), schema).getConfiguration())
                .target(MigrationVersion.fromVersion(target))
                .load();
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }
}
