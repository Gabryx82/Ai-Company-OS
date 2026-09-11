package com.aicompany.backend.persistence;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Test
    void initialMigrationIsApplied() {

        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);

        assertThat(versions).contains("1");
    }

    @Test
    void schemaContainsOnlyTheMigratedTables() {

        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'public' ORDER BY table_name",
                String.class);

        // Nothing beyond the migrated tables and Flyway's own history: proof that
        // Hibernate did not add anything of its own.
        assertThat(tables).containsExactly("agents", "flyway_schema_history", "tasks");
    }

    @Test
    void requiredColumnsAreNotNullable() {

        assertThat(nullabilityOf("tasks", "title")).isEqualTo("NO");
        assertThat(nullabilityOf("tasks", "status")).isEqualTo("NO");
        assertThat(nullabilityOf("tasks", "priority")).isEqualTo("NO");
        assertThat(nullabilityOf("agents", "name")).isEqualTo("NO");
        assertThat(nullabilityOf("agents", "active")).isEqualTo("NO");

        // description stays optional on purpose
        assertThat(nullabilityOf("tasks", "description")).isEqualTo("YES");
    }

    @Test
    void descriptionKeepsItsDeclaredLength() {

        Integer length = jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_name = 'tasks' AND column_name = 'description'",
                Integer.class);

        assertThat(length).isEqualTo(5000);
    }

    private String nullabilityOf(String table, String column) {
        return jdbc.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = ? AND column_name = ?",
                String.class, table, column);
    }
}
