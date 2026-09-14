package com.aicompany.backend.persistence;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that the schema comes from versioned migrations and that Hibernate,
 * running in validate mode, does not alter it.
 */
class SchemaMigrationTest extends AbstractPostgresTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${spring.jpa.hibernate.ddl-auto}")
    private String ddlAuto;

    @Test
    void schemaMigrationsAreAppliedInOrder() {

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);

        // Schema versions only, in order. The development seed is a separate
        // Flyway stream and must never appear here, otherwise the next schema
        // migration becomes out of order (R1).
        assertThat(versions).containsExactly("1", "2", "3", "4");
    }

    @Test
    void hibernateOnlyValidatesTheSchema() {

        assertThat(ddlAuto).isEqualTo("validate");
    }

    @Test
    void schemaContainsOnlyTheMigratedTables() {

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' ORDER BY table_name",
                String.class);

        // Nothing beyond the migrated tables and Flyway's own history: proof that
        // Hibernate did not add anything of its own.
        assertThat(tables)
                .containsExactly("agents", "flyway_schema_history", "projects", "tasks")
                .doesNotContain("flyway_dev_seed_history");
    }

    @Test
    void requiredColumnsAreNotNullable() {

        assertThat(nullabilityOf("tasks", "title")).isEqualTo("NO");
        assertThat(nullabilityOf("tasks", "status")).isEqualTo("NO");
        assertThat(nullabilityOf("tasks", "priority")).isEqualTo("NO");
        assertThat(nullabilityOf("agents", "name")).isEqualTo("NO");
        assertThat(nullabilityOf("agents", "active")).isEqualTo("NO");

        assertThat(nullabilityOf("projects", "name")).isEqualTo("NO");
        assertThat(nullabilityOf("projects", "status")).isEqualTo("NO");
        assertThat(nullabilityOf("projects", "created_at")).isEqualTo("NO");
        assertThat(nullabilityOf("projects", "updated_at")).isEqualTo("NO");

        // description stays optional on purpose
        assertThat(nullabilityOf("tasks", "description")).isEqualTo("YES");
        assertThat(nullabilityOf("projects", "description")).isEqualTo("YES");

        // And so does the project of a task, for this phase only: the rows that
        // existed before V3 have no project, and nothing may invent one for them.
        // See ADR-005 §1 -- tightening this to NOT NULL is a later decision with
        // a migration of its own, not a detail.
        assertThat(nullabilityOf("tasks", "project_id")).isEqualTo("YES");
    }

    @Test
    void taskProjectRelationIsEnforcedByTheDatabase() {

        // The service check is what makes the error readable; this is what makes
        // it true. A task row cannot point at a project that is not there,
        // whoever writes it.
        List<String> foreignKeys = jdbc.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                        + "WHERE table_name = 'tasks' AND constraint_type = 'FOREIGN KEY' "
                        + "ORDER BY constraint_name",
                String.class);

        assertThat(foreignKeys).containsExactly("tasks_project_id_fkey");

        // Deleting a project out from under its tasks must be refused rather than
        // cascade: the Company OS archives projects, it does not delete them
        // (ADR-004 §3), so NO ACTION is the deliberate choice here.
        String deleteRule = jdbc.queryForObject(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc "
                        + "WHERE rc.constraint_name = 'tasks_project_id_fkey'",
                String.class);

        assertThat(deleteRule).isEqualTo("NO ACTION");
    }

    @Test
    void tasksAreIndexedByProject() {

        // PostgreSQL does not index the referencing side of a foreign key on its
        // own, and "the tasks of this project" is the read the relation exists for.
        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'tasks' ORDER BY indexname",
                String.class);

        assertThat(indexes).contains("tasks_project_id_idx");
    }

    @Test
    void projectTimestampsAreStoredWithATimeZone() {

        // Instant round-trips correctly only if the column keeps the offset;
        // a plain "timestamp without time zone" would quietly drop it.
        assertThat(typeOf("projects", "created_at")).isEqualTo("timestamp with time zone");
        assertThat(typeOf("projects", "updated_at")).isEqualTo("timestamp with time zone");
    }

    @Test
    void projectRegistryCarriesItsIndexes() {

        List<String> indexes = jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'projects' ORDER BY indexname",
                String.class);

        assertThat(indexes).contains("projects_name_unique_idx", "projects_status_idx");
    }

    @Test
    void descriptionKeepsItsDeclaredLength() {

        Integer length = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'tasks' AND column_name = 'description'",
                Integer.class);

        assertThat(length).isEqualTo(5000);
    }

    private String typeOf(String table, String column) {
        return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }

    private String nullabilityOf(String table, String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}
