package com.aicompany.backend.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Regressions for the migration strategy itself (TASK-001A, Codex review R1/R2).
 *
 * <p>These tests drive Flyway directly instead of booting Spring: each case needs
 * a database in a specific historical state — empty, seeded, or carrying the
 * pre-fix {@code V1000} row — which an application context cannot express. Every
 * case gets its own PostgreSQL schema inside one shared container, so the cases
 * stay independent and no local volume is ever touched.
 *
 * <p>{@code classpath:db/fixture/next} stands in for the next schema migration and
 * {@code classpath:db/fixture/legacy} rebuilds the pre-fix layout. Both live under
 * {@code src/test/resources} and are never resolved by the application.
 */
@Testcontainers
class MigrationStreamTest {

    private static final String SCHEMA_LOCATION = "classpath:db/migration";
    private static final String NEXT_MIGRATION_FIXTURE = "classpath:db/fixture/next";
    private static final String LEGACY_SEED_FIXTURE = "classpath:db/fixture/legacy";

    /**
     * Version of the probe fixture. It has to sit above the head of the real
     * schema stream and below the legacy 1000; see the fixture itself.
     */
    private static final String PROBE_VERSION = "900";

    private static final List<String> SEEDED_AGENTS =
            List.of("Code Architect", "Database Specialist", "Frontend Developer");

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    // AC-1 — an empty database is migrated by the schema stream alone.
    @Test
    void emptyDatabaseIsMigratedByTheSchemaStreamAlone() {

        String schema = "ac1_empty";

        MigrateResult result = schemaFlyway(schema).migrate();

        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(resolvedSchemaVersions().size());

        // The schema stream carries schema versions only: no seed version leaks
        // into it any more, whatever the current head happens to be.
        assertThat(appliedSchemaVersions(schema)).isEqualTo(resolvedSchemaVersions());
        assertThat(tablesIn(schema))
                .containsExactly("agents", "flyway_schema_history", "projects", "tasks")
                .doesNotContain(DevSeedFlyway.HISTORY_TABLE);

        // Nothing seeded it: the schema stream carries no data.
        assertThat(agentCount(schema)).isZero();
    }

    // AC-2 — the dev seed applies, in its own history, and never duplicates.
    @Test
    void devSeedAppliesInItsOwnHistoryAndDoesNotDuplicate() {

        String schema = "ac2_seed";
        schemaFlyway(schema).migrate();

        MigrateResult firstRun = DevSeedFlyway.apply(dataSource(), schema);

        assertThat(firstRun.migrationsExecuted).isEqualTo(1);
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);

        // The seed is recorded in its own table and stays out of the schema history.
        assertThat(appliedSchemaVersions(schema)).isEqualTo(resolvedSchemaVersions());

        // "0" is the baseline row the seed stream writes because the schema stream
        // already populated the schema; "1" is the seed migration itself.
        assertThat(appliedSeedVersions(schema)).containsExactly("0", "1");

        // Re-running both streams, as every application restart does.
        MigrateResult secondRun = DevSeedFlyway.apply(dataSource(), schema);
        schemaFlyway(schema).migrate();

        assertThat(secondRun.migrationsExecuted).isZero();
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);

        // The SQL guard holds on its own, independently of Flyway's history:
        // replaying the statement is what the legacy transition relies on.
        replaySeedStatement(schema);
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);
    }

    // AC-3 — a future V2 applies to an already seeded development database.
    @Test
    void futureSchemaMigrationAppliesToASeededDatabase() {

        String schema = "ac3_upgrade";
        schemaFlyway(schema).migrate();
        DevSeedFlyway.apply(dataSource(), schema);

        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) VALUES (?, ?, ?)",
                "pre-existing task", "OPEN", "HIGH");

        // This is the case that failed before TASK-001A with
        // "Detected resolved migration not applied to database: <probe>".
        MigrateResult upgrade = schemaFlyway(schema, NEXT_MIGRATION_FIXTURE).migrate();

        assertThat(upgrade.success).isTrue();
        assertThat(upgrade.migrationsExecuted).isEqualTo(1);
        assertThat(appliedSchemaVersions(schema)).isEqualTo(schemaVersionsPlusProbe());
        assertThat(columnsOf(schema, "tasks")).contains("probe_marker");

        // Data written before the upgrade survives it, seed included.
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);
        assertThat(taskTitles(schema)).containsExactly("pre-existing task");
    }

    // AC-4 — production is independent of the development seed.
    @Test
    void productionStreamIsIndependentOfTheDevelopmentSeed() {

        String schema = "ac4_prod";
        schemaFlyway(schema).migrate();
        DevSeedFlyway.apply(dataSource(), schema);

        // A production configuration resolves the schema location only. It must
        // neither fail validation nor see anything left to do.
        Flyway production = schemaFlyway(schema);
        production.validate();

        assertThat(production.migrate().migrationsExecuted).isZero();
        assertThat(production.info().current().getVersion().getVersion())
                .isEqualTo(resolvedSchemaVersions().getLast());
        assertThat(Arrays.stream(production.info().all()).map(MigrationInfo::getScript))
                .doesNotContain(DevSeedFlyway.LEGACY_SEED_SCRIPT, "V1__dev_seed_agents.sql");

        // And it can still move forward, which is the whole point of the fix.
        assertThat(schemaFlyway(schema, NEXT_MIGRATION_FIXTURE).migrate().migrationsExecuted)
                .isEqualTo(1);

        assertThat(appliedSchemaVersions(schema)).isEqualTo(schemaVersionsPlusProbe());
    }

    // AC-4, second half — a production database that was never seeded.
    @Test
    void productionStreamOnAnUnseededDatabaseHasNoSeedArtefacts() {

        String schema = "ac4_clean_prod";

        schemaFlyway(schema).migrate();
        schemaFlyway(schema, NEXT_MIGRATION_FIXTURE).migrate();

        assertThat(appliedSchemaVersions(schema)).isEqualTo(schemaVersionsPlusProbe());
        assertThat(tablesIn(schema)).doesNotContain(DevSeedFlyway.HISTORY_TABLE);
        assertThat(agentCount(schema)).isZero();
    }

    // AC-5 — a legacy database carrying V1000 is recoverable, without duplicates.
    @Test
    void legacyDatabaseCarryingV1000IsRecoverable() {

        String schema = "ac5_legacy";

        // Rebuild the pre-fix state: schema and seed in one versioned stream.
        schemaFlyway(schema, LEGACY_SEED_FIXTURE).migrate();
        assertThat(appliedSchemaVersions(schema)).isEqualTo(schemaVersionsPlus("1000"));
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);

        // R1 reproduced: with 1000 in the schema history, a lower-numbered
        // migration that has not run yet cannot be applied.
        assertThatThrownBy(() -> schemaFlyway(schema, NEXT_MIGRATION_FIXTURE).migrate())
                .hasMessageContaining(
                        "Detected resolved migration not applied to database: " + PROBE_VERSION);

        // The documented transition: drop the one legacy row, nothing else.
        assertThat(DevSeedFlyway.removeLegacySeedHistory(dataSource(), schema)).isTrue();
        assertThat(appliedSchemaVersions(schema)).isEqualTo(resolvedSchemaVersions());

        // Seeded rows were not deleted, and the seed stream does not re-insert them.
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);
        assertThat(DevSeedFlyway.apply(dataSource(), schema).migrationsExecuted).isEqualTo(1);
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);

        // And the upgrade that used to be blocked now works.
        assertThat(schemaFlyway(schema, NEXT_MIGRATION_FIXTURE).migrate().migrationsExecuted)
                .isEqualTo(1);
        assertThat(appliedSchemaVersions(schema)).isEqualTo(schemaVersionsPlusProbe());
    }

    @Test
    void removingLegacyHistoryIsANoOpWhenThereIsNothingToRemove() {

        String schema = "ac5_noop";

        // No history table at all yet.
        assertThat(DevSeedFlyway.removeLegacySeedHistory(dataSource(), schema)).isFalse();

        schemaFlyway(schema).migrate();

        // History table present, but no legacy row in it.
        assertThat(DevSeedFlyway.removeLegacySeedHistory(dataSource(), schema)).isFalse();
        assertThat(appliedSchemaVersions(schema)).isEqualTo(resolvedSchemaVersions());
    }

    // --- helpers -----------------------------------------------------------

    /**
     * The versions the schema stream resolves right now, read from Flyway
     * itself rather than hard-coded. Every migration a later task adds extends
     * this list automatically, so these regressions keep testing the migration
     * strategy instead of the current head.
     */
    private static List<String> resolvedSchemaVersions() {
        return Arrays.stream(Flyway.configure()
                        .dataSource(dataSource())
                        .locations(SCHEMA_LOCATION)
                        .load()
                        .info()
                        .all())
                .map(info -> info.getVersion().getVersion())
                .toList();
    }

    private static List<String> schemaVersionsPlusProbe() {
        return schemaVersionsPlus(PROBE_VERSION);
    }

    private static List<String> schemaVersionsPlus(String extraVersion) {
        return Stream.concat(resolvedSchemaVersions().stream(), Stream.of(extraVersion)).toList();
    }

    private static Flyway schemaFlyway(String schema, String... extraLocations) {

        String[] locations = new String[extraLocations.length + 1];
        locations[0] = SCHEMA_LOCATION;
        System.arraycopy(extraLocations, 0, locations, 1, extraLocations.length);

        return Flyway.configure()
                .dataSource(dataSource())
                .locations(locations)
                .schemas(schema)
                .load();
    }

    private static DataSource dataSource() {
        return new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    /**
     * Runs the seed INSERT a second time outside Flyway, to show the guard in the
     * SQL itself — not only the schema history — is what prevents duplicates.
     */
    private void replaySeedStatement(String schema) {

        jdbc().update("INSERT INTO \"" + schema + "\".agents (name, role, specialization, active) "
                + "SELECT seed.name, seed.role, seed.specialization, seed.active FROM (VALUES "
                + "('Code Architect', 'Software Engineer', 'replayed', TRUE), "
                + "('Frontend Developer', 'Frontend Engineer', 'replayed', TRUE), "
                + "('Database Specialist', 'Database Engineer', 'replayed', TRUE)"
                + ") AS seed(name, role, specialization, active) WHERE NOT EXISTS "
                + "(SELECT 1 FROM \"" + schema + "\".agents existing WHERE existing.name = seed.name)");
    }

    private List<String> appliedSchemaVersions(String schema) {
        return historyVersions(schema, DevSeedFlyway.SCHEMA_HISTORY_TABLE);
    }

    private List<String> appliedSeedVersions(String schema) {
        return historyVersions(schema, DevSeedFlyway.HISTORY_TABLE);
    }

    private List<String> historyVersions(String schema, String table) {
        return jdbc().queryForList(
                "SELECT version FROM \"" + schema + "\".\"" + table + "\" "
                        + "WHERE success = TRUE AND version IS NOT NULL ORDER BY installed_rank",
                String.class);
    }

    private List<String> tablesIn(String schema) {
        return jdbc().queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ? "
                        + "ORDER BY table_name",
                String.class, schema);
    }

    private List<String> columnsOf(String schema, String table) {
        return jdbc().queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = ? ORDER BY column_name",
                String.class, schema, table);
    }

    private List<String> agentNames(String schema) {
        return jdbc().queryForList(
                "SELECT name FROM \"" + schema + "\".agents ORDER BY name", String.class);
    }

    private Integer agentCount(String schema) {
        return jdbc().queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".agents", Integer.class);
    }

    private List<String> taskTitles(String schema) {
        return jdbc().queryForList(
                "SELECT title FROM \"" + schema + "\".tasks ORDER BY id", String.class);
    }
}
