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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The development seed has a single owner — a versioned migration under
 * {@code db/dev} — applied only under the "dev" profile, and it lives in a Flyway
 * stream of its own.
 *
 * <p>This test runs the real application context. {@link MigrationStreamTest}
 * covers the stream mechanics on databases in states a context cannot express.
 */
@SpringBootTest
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

    @Test
    void devProfileSeedsTheDemonstrationAgents() {

        assertThat(agentRepository.findAll())
                .hasSize(3)
                .extracting("name")
                .containsExactlyInAnyOrder("Code Architect", "Frontend Developer", "Database Specialist");
    }

    @Test
    void theSeedIsRecordedInItsOwnHistoryNotInTheSchemaHistory() {

        // The schema stream is the same one production runs: schema versions and
        // nothing else. No seed version may ever appear here.
        assertThat(versionsIn(DevSeedFlyway.SCHEMA_HISTORY_TABLE))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16");

        // The seed keeps its own history, so it never constrains schema versions.
        // "0" is the baseline row written because the schema stream had already
        // created the tables; "1" is the seed migration.
        assertThat(versionsIn(DevSeedFlyway.HISTORY_TABLE)).containsExactly("0", "1");
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
