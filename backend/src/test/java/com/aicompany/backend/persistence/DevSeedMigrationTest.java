package com.aicompany.backend.persistence;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.support.PostgresTestcontainerConfig;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The development seed has a single owner: a versioned migration applied only
 * when the "dev" profile adds classpath:db/dev to the Flyway locations.
 */
@SpringBootTest
@ActiveProfiles("dev")
@Import(PostgresTestcontainerConfig.class)
class DevSeedMigrationTest {

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private Flyway flyway;

    @Test
    void devProfileSeedsTheDemonstrationAgents() {

        assertThat(agentRepository.findAll())
                .hasSize(3)
                .extracting("name")
                .containsExactlyInAnyOrder("Code Architect", "Frontend Developer", "Database Specialist");
    }

    @Test
    void runningMigrationsAgainDoesNotDuplicateTheSeed() {

        long before = agentRepository.count();

        // Flyway's schema history, not an application-level emptiness check, is
        // what keeps the seed from being applied twice.
        flyway.migrate();

        assertThat(agentRepository.count()).isEqualTo(before).isEqualTo(3);
    }
}
