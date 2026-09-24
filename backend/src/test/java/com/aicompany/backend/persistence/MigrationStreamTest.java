package com.aicompany.backend.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * The tables the schema stream is expected to have created by its current
     * head. Written by hand, and that is the decision rather than an oversight:
     * a migration that creates a table is a fact about the system, and whoever
     * adds one should have to say so here. The versions above are read from
     * Flyway; this is not, and the difference is deliberate.
     *
     * <p>What TD-23 recorded was not this list but the sentence that used to sit
     * next to it, which described the test as adapting to new tables on its own.
     * It does not, and a wrong explanation is what somebody reasons from later.
     */
    private static final List<String> TABLES_AT_HEAD =
            List.of("agents", "flyway_schema_history", "llm_models", "model_providers", "plan_runs",
                    "project_phases", "projects", "quota_plans", "software", "task_handoffs", "task_reviews",
                    "task_runs", "tasks");

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
                .containsExactlyElementsOf(TABLES_AT_HEAD)
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

    /**
     * TASK-003 — the real V2 to V3 upgrade, on a database that already holds
     * data, rather than on an empty one.
     *
     * <p>This is the case a fresh {@code migrate} never exercises: every other
     * test here builds the schema in one go, so a migration that silently
     * depended on an empty table would still pass. Flyway's {@code target} stops
     * the stream at V2, the test writes the rows a running installation would
     * have, and only then lets V3 run.
     */
    @Test
    void taskProjectRelationIsAddedToAPopulatedV2Database() {

        String schema = "task003_v2_to_v3";

        // 1. A database at V2: projects exist, tasks exist, they are unrelated.
        MigrateResult toV2 = schemaFlywayUpTo(schema, "2").migrate();
        assertThat(toV2.success).isTrue();
        assertThat(appliedSchemaVersions(schema)).containsExactly("1", "2");
        assertThat(columnsOf(schema, "tasks")).doesNotContain("project_id");

        seedAgentsAt(schema);

        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, description, status, priority) "
                + "VALUES (?, ?, ?, ?)", "task written before V3", "kept as it was", "OPEN", "HIGH");
        jdbc().update("INSERT INTO \"" + schema + "\".projects "
                + "(name, description, status, created_at, updated_at) "
                + "VALUES (?, ?, 'ACTIVE', now(), now())", "Project written before V3", null);

        // 2. The upgrade under test: V3 and only V3.
        //
        //    This used to say "migrate to head", which meant the same thing while
        //    V3 was the head and silently stopped meaning it when V4 arrived. The
        //    step this test is about is named now, so a later migration cannot
        //    quietly widen what it covers. Every other step, including V3 to V4,
        //    is covered by everyConsecutiveUpgradePreservesWhatWasAlreadyThere.
        MigrateResult toV3 = schemaFlywayUpTo(schema, "3").migrate();

        assertThat(toV3.success).isTrue();
        assertThat(toV3.migrationsExecuted).isEqualTo(1);
        assertThat(appliedSchemaVersions(schema)).containsExactly("1", "2", "3");

        // 3. The relation exists, and the rows that predate it are untouched --
        //    same values, and explicitly no project rather than an invented one
        //    (ADR-005 §2).
        assertThat(columnsOf(schema, "tasks")).contains("project_id");
        assertThat(taskTitles(schema)).containsExactly("task written before V3");
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".tasks WHERE project_id IS NOT NULL",
                Integer.class))
                .isZero();

        assertThat(jdbc().queryForObject(
                "SELECT description FROM \"" + schema + "\".tasks WHERE title = ?",
                String.class, "task written before V3"))
                .isEqualTo("kept as it was");

        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".projects", Integer.class))
                .isEqualTo(1);

        // 4. A pre-existing task can be assigned after the upgrade, and the
        //    foreign key refuses an identifier that resolves to nothing.
        Long projectId = jdbc().queryForObject(
                "SELECT id FROM \"" + schema + "\".projects", Long.class);

        jdbc().update("UPDATE \"" + schema + "\".tasks SET project_id = ? WHERE title = ?",
                projectId, "task written before V3");

        assertThatThrownBy(() -> jdbc().update(
                "UPDATE \"" + schema + "\".tasks SET project_id = 987654 WHERE title = ?",
                "task written before V3"))
                .isInstanceOf(DataIntegrityViolationException.class);

        // 5. And the stream can still move forward from here: everything after
        //    V3, plus the probe. The count is derived rather than written down,
        //    so a V5 does not turn this into a failure about the wrong thing.
        MigrateResult onwards = schemaFlyway(schema, NEXT_MIGRATION_FIXTURE).migrate();

        assertThat(onwards.migrationsExecuted)
                .isEqualTo(schemaVersionsPlusProbe().size() - 3);
        assertThat(appliedSchemaVersions(schema)).isEqualTo(schemaVersionsPlusProbe());
    }

    /**
     * TASK-007, invariant I-2 -- the real V3 to V4 upgrade on a populated
     * database, with the part the generic step test cannot see.
     *
     * <p>{@code everyConsecutiveUpgradePreservesWhatWasAlreadyThere} already
     * covers this step for row and table survival, and covers it without anybody
     * adding a case, which was the point of writing it that way. What it counts
     * is rows; what it cannot tell is whether a backfilled column actually got a
     * value, or whether the unique index the same migration creates would have
     * rejected the data already there.
     *
     * <p>Both of those are specific to V4, so they are asserted here rather than
     * being pushed into a test that must stay true of every migration.
     */
    @Test
    void agentRegistryColumnsAreBackfilledOnAPopulatedV3Database() {

        String schema = "task007_v3_to_v4";

        // A database at V3, with agents that predate the new columns.
        schemaFlywayUpTo(schema, "3").migrate();
        assertThat(columnsOf(schema, "agents")).doesNotContain("created_at", "updated_at");

        seedAgentsAt(schema);
        insertAgentAt(schema, "Written before V4", "Engineer", "legacy", true);

        MigrateResult toV4 = schemaFlywayUpTo(schema, "4").migrate();

        assertThat(toV4.migrationsExecuted).isEqualTo(1);
        assertThat(columnsOf(schema, "agents")).contains("created_at", "updated_at");

        // Every pre-existing row got a value. NOT NULL guarantees they are not
        // null; what this asserts is that the backfill ran before the constraint
        // did, rather than the migration having failed on an empty table.
        assertThat(agentNames(schema)).contains("Written before V4").containsAll(SEEDED_AGENTS);
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".agents "
                        + "WHERE created_at IS NULL OR updated_at IS NULL", Integer.class))
                .isZero();

        // The timestamps carry a zone. An Instant written to a column without one
        // loses its offset silently and comes back plausible and wrong, which is
        // why ADR-004 section 7 asserts the type rather than trusting validate mode.
        assertThat(jdbc().queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_schema = ? AND table_name = 'agents' AND column_name = 'created_at'",
                String.class, schema))
                .isEqualTo("timestamp with time zone");

        // And the index the same migration creates is real: a name differing only
        // by case is refused from here on.
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO \"" + schema + "\".agents (name, role, specialization, active) "
                        + "VALUES (?, ?, ?, TRUE)", "WRITTEN BEFORE V4", "Engineer", "x"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * TASK-010, invariants I-5 and I-6 -- the real V6 to V7 upgrade on a
     * populated database.
     *
     * <p>V7 is the first migration in this stream that is <strong>not additive by
     * construction</strong>. Every one before it added a column, a table or an
     * index; this one applies a constraint to rows that already exist, and
     * PostgreSQL validates every one of them as it does so. That makes the
     * question "does it apply cleanly to a database that is already in use"
     * something to answer rather than assume.
     *
     * <p>{@code everyConsecutiveUpgradePreservesWhatWasAlreadyThere} already
     * covers the row and table survival of this step, and covers it without
     * anybody adding a case -- it writes an {@code OPEN} task at V6 before
     * migrating to V7, which is exactly this scenario. What it cannot tell is
     * whether the constraint the migration creates is real afterwards, which is
     * specific to V7 and therefore asserted here.
     */
    @Test
    void taskStatusVocabularyIsAppliedToAPopulatedV6Database() {

        String schema = "task010_v6_to_v7";

        // A database at V6, with a task that predates the constraint -- and where
        // a value outside the vocabulary is still perfectly legal.
        schemaFlywayUpTo(schema, "6").migrate();
        assertThat(appliedSchemaVersions(schema)).doesNotContain("7");

        seedAgentsAt(schema);
        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                + "VALUES (?, ?, ?)", "written before V7", "OPEN", "HIGH");

        Map<String, Integer> before = rowCountsIn(schema);

        MigrateResult toV7 = schemaFlywayUpTo(schema, "7").migrate();

        assertThat(toV7.success).isTrue();
        assertThat(toV7.migrationsExecuted).isEqualTo(1);

        // Nothing was rewritten to make the constraint fit. The row is there and
        // says what it said, with the spelling it had.
        assertThat(rowCountsIn(schema)).isEqualTo(before);
        assertThat(taskTitles(schema)).contains("written before V7");
        assertThat(jdbc().queryForObject(
                "SELECT status FROM \"" + schema + "\".tasks WHERE title = ?",
                String.class, "written before V7"))
                .isEqualTo("OPEN");

        // And the constraint is real from here on, on both the paths a check
        // covers. Asserting only the insert would leave the row reachable in one
        // UPDATE while this test still passed.
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO \"" + schema + "\".tasks (title, status, priority) VALUES (?, ?, ?)",
                "written after V7", "banana", "HIGH"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc().update(
                "UPDATE \"" + schema + "\".tasks SET status = ? WHERE title = ?",
                "banana", "written before V7"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * TASK-016 (TD-36) -- the real V8 to V9 upgrade on a populated database. Both
     * values the census found in real data are seeded, not one: with a single
     * value a constraint that accepted only that value would pass this test.
     */
    @Test
    void taskPriorityVocabularyIsAppliedToAPopulatedV8Database() {

        String schema = "task016_v8_to_v9";

        schemaFlywayUpTo(schema, "8").migrate();
        seedAgentsAt(schema);
        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                + "VALUES (?, ?, ?)", "high before V9", "OPEN", "HIGH");
        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                + "VALUES (?, ?, ?)", "low before V9", "DONE", "LOW");

        Map<String, Integer> before = rowCountsIn(schema);

        MigrateResult toV9 = schemaFlywayUpTo(schema, "9").migrate();

        assertThat(toV9.success).isTrue();
        assertThat(toV9.migrationsExecuted).isEqualTo(1);
        assertThat(rowCountsIn(schema)).isEqualTo(before);
        assertThat(jdbc().queryForList(
                "SELECT priority FROM \"" + schema + "\".tasks ORDER BY title", String.class))
                .containsExactly("HIGH", "LOW");

        // The third member of the vocabulary is accepted from here on ...
        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                + "VALUES (?, ?, ?)", "medium after V9", "OPEN", "MEDIUM");

        // ... and nothing outside it is, on either path.
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO \"" + schema + "\".tasks (title, status, priority) VALUES (?, ?, ?)",
                "urgent after V9", "OPEN", "URGENT"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc().update(
                "UPDATE \"" + schema + "\".tasks SET priority = ? WHERE title = ?",
                "high", "high before V9"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** TASK-016 -- the declared risk of V9: it stops, and leaves the row as it was. */
    @Test
    void taskPriorityVocabularyStopsOnADatabaseThatHoldsAValueOutsideIt() {

        String schema = "task016_v9_refuses";

        schemaFlywayUpTo(schema, "8").migrate();
        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                + "VALUES (?, ?, ?)", "priority nobody declared", "OPEN", "whenever");

        assertThatThrownBy(() -> schemaFlywayUpTo(schema, "9").migrate())
                .isInstanceOf(FlywayException.class);

        assertThat(appliedSchemaVersions(schema)).doesNotContain("9");
        assertThat(jdbc().queryForObject(
                "SELECT priority FROM \"" + schema + "\".tasks WHERE title = ?",
                String.class, "priority nobody declared"))
                .isEqualTo("whenever");
    }

    /**
     * TASK-010, invariant I-6 -- the declared risk of V7, made executable.
     *
     * <p>On a database holding a status outside the vocabulary, this migration
     * <strong>fails</strong>. That is the intended behaviour and not a defect,
     * and it is the precedent V4 set for {@code agents_name_unique_idx}: a
     * migration that stops is the conversation that makes somebody decide. The
     * alternative would be to rewrite values it does not recognise, in silence,
     * which means inventing a mapping on behalf of whoever wrote them.
     *
     * <p>The census that preceded this task found no such value anywhere
     * ({@code tasks/TASK-010/CENSUS.md}), so this case cannot arise from this
     * repository's own data. It is written because the risk was declared, and a
     * declared risk that nothing exercises is a sentence in a comment.
     *
     * <p>The second half matters as much as the first: the failed migration must
     * leave the offending row <em>intact</em>. A migration that stopped after
     * destroying what it could not classify would be worse than one that
     * succeeded.
     */
    @Test
    void taskStatusVocabularyStopsOnADatabaseThatHoldsAValueOutsideIt() {

        String schema = "task010_v7_refuses";

        schemaFlywayUpTo(schema, "6").migrate();

        jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                + "VALUES (?, ?, ?)", "status nobody declared", "banana", "HIGH");

        assertThatThrownBy(() -> schemaFlywayUpTo(schema, "7").migrate())
                .isInstanceOf(FlywayException.class);

        // V7 did not record itself as applied ...
        assertThat(appliedSchemaVersions(schema)).doesNotContain("7");

        // ... and the row it could not classify is untouched.
        assertThat(jdbc().queryForObject(
                "SELECT status FROM \"" + schema + "\".tasks WHERE title = ?",
                String.class, "status nobody declared"))
                .isEqualTo("banana");
    }

    /**
     * TD-31 (ADR-012) -- the real V7 to V8 upgrade on a populated database, and
     * the one property the whole migration rests on: the backfill is a
     * <strong>bijection</strong>.
     *
     * <p>V8 is the first migration in this stream that <strong>destroys</strong>
     * something: it drops {@code agents.active}. ADR-008 §2 argued the change was
     * lossless because a boolean maps onto two values injectively and totally,
     * and this is that argument executed rather than repeated -- both sides of
     * the bijection are seeded before the upgrade and checked after it.
     *
     * <p>Row survival across this step is already covered, without anybody
     * adding a case, by {@code everyConsecutiveUpgradePreservesWhatWasAlreadyThere}.
     * What is specific to V8, and therefore here: that each row kept the state it
     * had, that the old column is gone rather than merely unused, and that the
     * new one is constrained.
     */
    @Test
    void agentLifecycleIsUnifiedOnAPopulatedV7Database() {

        String schema = "td031_v7_to_v8";

        // A database at V7, where the lifecycle is still a boolean.
        schemaFlywayUpTo(schema, "7").migrate();
        assertThat(columnsOf(schema, "agents")).contains("active").doesNotContain("status");

        // Both sides of the bijection, so neither direction can be assumed. A
        // test with only active agents would pass against a migration that wrote
        // 'ACTIVE' unconditionally.
        jdbc().update("INSERT INTO \"" + schema + "\".agents (name, role, specialization, active) "
                + "VALUES (?, ?, ?, TRUE)", "Still working", "Engineer", "x");
        jdbc().update("INSERT INTO \"" + schema + "\".agents (name, role, specialization, active) "
                + "VALUES (?, ?, ?, FALSE)", "Switched off", "Engineer", "y");

        Map<String, Integer> before = rowCountsIn(schema);

        MigrateResult toV8 = schemaFlywayUpTo(schema, "8").migrate();

        assertThat(toV8.success).isTrue();
        assertThat(toV8.migrationsExecuted).isEqualTo(1);

        // The column TD-31 exists to remove is gone, and the new one is there.
        assertThat(columnsOf(schema, "agents")).contains("status").doesNotContain("active");

        // No row appeared or vanished while the table was rewritten.
        assertThat(rowCountsIn(schema)).isEqualTo(before);

        // The bijection, both ways, on the rows written before the migration.
        assertThat(jdbc().queryForObject(
                "SELECT status FROM \"" + schema + "\".agents WHERE name = ?",
                String.class, "Still working"))
                .isEqualTo("ACTIVE");

        assertThat(jdbc().queryForObject(
                "SELECT status FROM \"" + schema + "\".agents WHERE name = ?",
                String.class, "Switched off"))
                .isEqualTo("INACTIVE");

        // Nothing is left unclassified.
        assertThat(jdbc().queryForObject(
                "SELECT count(*) FROM \"" + schema + "\".agents WHERE status IS NULL", Integer.class))
                .isZero();

        // And the constraint the migration creates is real from here on.
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO \"" + schema + "\".agents (name, role, specialization, status) "
                        + "VALUES (?, ?, ?, ?)", "Bad state", "Engineer", "z", "RETIRED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * TD-31, the consequence that was nearly missed: the dev seed names the
     * column V8 drops.
     *
     * <p>On a database that has <strong>already seeded</strong>, the edited seed
     * has a different checksum from the one in {@code flyway_dev_seed_history},
     * and Flyway validates checksums on migrate. Without the {@code repair()}
     * that {@code DevSeedFlyway.apply} now runs first, every such database would
     * refuse to start in the dev profile -- which is the single most likely way
     * this change could have broken a real machine, since the development
     * database in this repository is one of them.
     *
     * <p>The stale checksum is written directly rather than by running an older
     * copy of the seed: what has to be proven is that {@code apply} survives a
     * recorded checksum that disagrees with the file, and its value is what makes
     * it disagree.
     */
    @Test
    void anAlreadySeededDatabaseSurvivesTheSeedBeingEdited() {

        String schema = "td031_seed_checksum";

        schemaFlyway(schema).migrate();
        DevSeedFlyway.apply(dataSource(), schema);
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);

        // The state a database is in after running a previous version of the
        // seed file: applied, but recorded against different bytes.
        jdbc().update("UPDATE \"" + schema + "\".\"" + DevSeedFlyway.HISTORY_TABLE + "\" "
                + "SET checksum = ? WHERE version = ?", 1, "1");

        // Must not throw, and must not re-run or duplicate anything.
        MigrateResult afterRepair = DevSeedFlyway.apply(dataSource(), schema);

        assertThat(afterRepair.success).isTrue();
        assertThat(afterRepair.migrationsExecuted).isZero();
        assertThat(agentNames(schema)).isEqualTo(SEEDED_AGENTS);
    }

    /**
     * TASK-006, TD-22 -- every consecutive step of the stream, on a database that
     * already holds data.
     *
     * <p>Every other test here builds the schema in one go. A migration that
     * silently depended on an empty table, or that dropped rows while rewriting
     * one, would pass all of them. TASK-003 wrote the equivalent of this for the
     * single step it introduced; what was missing was the same guarantee for the
     * other steps, and for the ones not written yet.
     *
     * <p>It walks the pairs Flyway resolves rather than a list written here, so a
     * V4 is covered the day it exists and nobody has to remember to add a case.
     * Three things are asserted at each step, and they are the three ways a
     * migration destroys something without failing: a row disappears, a table
     * disappears, or more than the expected migration runs.
     *
     * <p>What it deliberately does not assert is that the schema afterwards looks
     * a particular way. That belongs to the test for the migration that made it
     * so; this one is about what must survive every step, whatever each step does.
     */
    @Test
    void everyConsecutiveUpgradePreservesWhatWasAlreadyThere() {

        List<String> versions = resolvedSchemaVersions();
        assertThat(versions)
                .as("a stream with fewer than two versions has no step to check, "
                        + "and this test would be silently vacuous")
                .hasSizeGreaterThan(1);

        for (int step = 0; step < versions.size() - 1; step++) {

            String from = versions.get(step);
            String to = versions.get(step + 1);
            String schema = "td22_v" + from + "_to_v" + to;

            schemaFlywayUpTo(schema, from).migrate();
            assertThat(appliedSchemaVersions(schema))
                    .as("step %s -> %s: the fixture must really stop at %s", from, to, from)
                    .containsExactlyElementsOf(versions.subList(0, step + 1));

            // Rows a running installation would have at this point, written
            // directly rather than through the seed stream.
            //
            // The seed used to be reusable here because agents.active existed at
            // every version. Since TD-31's V8 replaced it with agents.status the
            // seed only runs against the head of the stream, so a fixture that
            // writes an agent at an ARBITRARY version has to ask which column the
            // schema has -- which is what seedAgentsAt does. The product is
            // unaffected: DevSeedFlywayConfiguration applies the seed only after
            // the full schema stream.
            seedAgentsAt(schema);
            jdbc().update("INSERT INTO \"" + schema + "\".tasks (title, status, priority) "
                    + "VALUES (?, ?, ?)", "written at V" + from, "OPEN", "HIGH");

            Map<String, Integer> before = rowCountsIn(schema);

            MigrateResult upgrade = schemaFlywayUpTo(schema, to).migrate();

            assertThat(upgrade.migrationsExecuted)
                    .as("step %s -> %s: exactly one migration should run", from, to)
                    .isEqualTo(1);

            Map<String, Integer> after = rowCountsIn(schema);

            assertThat(after.keySet())
                    .as("step %s -> %s: a migration must not drop a table that already existed",
                            from, to)
                    .containsAll(before.keySet());

            before.forEach((table, count) -> assertThat(after.get(table))
                    .as("step %s -> %s: rows in '%s' must survive the upgrade", from, to, table)
                    .isGreaterThanOrEqualTo(count));

            assertThat(taskTitles(schema))
                    .as("step %s -> %s: the task written before the upgrade is still there",
                            from, to)
                    .contains("written at V" + from);
            assertThat(agentNames(schema))
                    .as("step %s -> %s: the seeded agents are still there", from, to)
                    .isEqualTo(SEEDED_AGENTS);
        }
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
     * itself rather than hard-coded, so every migration a later task adds extends
     * this list on its own and these regressions keep testing the migration
     * strategy rather than the current head.
     *
     * <p>That applies to versions only. The table list is declared by hand in
     * {@link #TABLES_AT_HEAD}, on purpose; claiming otherwise was TD-23.
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

    /**
     * The schema stream stopped at one version, so a test can put a database in
     * a historical state and migrate forward from there.
     */
    private static Flyway schemaFlywayUpTo(String schema, String version) {
        return Flyway.configure()
                .dataSource(dataSource())
                .locations(SCHEMA_LOCATION)
                .schemas(schema)
                .target(MigrationVersion.fromVersion(version))
                .load();
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
     * The three demonstration agents, written directly, at whatever schema
     * version this database currently is.
     *
     * <p>Used where a test needs "a running installation would have agents here"
     * at an intermediate version. It cannot go through {@link DevSeedFlyway}
     * because the seed statement names the lifecycle column, and since TD-31 that
     * column differs before and after {@code V8}.
     */
    private void seedAgentsAt(String schema) {
        for (String name : SEEDED_AGENTS) {
            insertAgentAt(schema, name, "Engineer", "seeded by the test fixture", true);
        }
    }

    /**
     * Inserts one agent using whichever lifecycle column the schema at this
     * version actually has: {@code active BOOLEAN} before {@code V8},
     * {@code status VARCHAR} from {@code V8} on.
     *
     * <p>TD-31 made the two mutually exclusive, so a fixture that writes an agent
     * across the whole stream has to look rather than assume. Reading
     * {@code information_schema} is the honest way to ask; hard-coding "before 8"
     * would put the version number in a second place.
     */
    private void insertAgentAt(String schema, String name, String role,
                               String specialization, boolean active) {

        if (columnsOf(schema, "agents").contains("status")) {
            jdbc().update("INSERT INTO \"" + schema + "\".agents "
                            + "(name, role, specialization, status) VALUES (?, ?, ?, ?)",
                    name, role, specialization, active ? "ACTIVE" : "INACTIVE");
        } else {
            jdbc().update("INSERT INTO \"" + schema + "\".agents "
                            + "(name, role, specialization, active) VALUES (?, ?, ?, ?)",
                    name, role, specialization, active);
        }
    }

    /**
     * Runs the seed INSERT a second time outside Flyway, to show the guard in the
     * SQL itself — not only the schema history — is what prevents duplicates.
     */
    private void replaySeedStatement(String schema) {

        jdbc().update("INSERT INTO \"" + schema + "\".agents (name, role, specialization, status) "
                + "SELECT seed.name, seed.role, seed.specialization, seed.status FROM (VALUES "
                + "('Code Architect', 'Software Engineer', 'replayed', 'ACTIVE'), "
                + "('Frontend Developer', 'Frontend Engineer', 'replayed', 'ACTIVE'), "
                + "('Database Specialist', 'Database Engineer', 'replayed', 'ACTIVE')"
                + ") AS seed(name, role, specialization, status) WHERE NOT EXISTS "
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

    /**
     * Row counts for every table in the schema, Flyway's own history tables
     * excluded: those legitimately grow as migrations are applied, and counting
     * them would turn "nothing was lost" into "nothing changed".
     */
    private Map<String, Integer> rowCountsIn(String schema) {

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String table : tablesIn(schema)) {
            if (table.endsWith("_history")) {
                continue;
            }
            counts.put(table, jdbc().queryForObject(
                    "SELECT count(*) FROM \"" + schema + "\".\"" + table + "\"", Integer.class));
        }
        return counts;
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
