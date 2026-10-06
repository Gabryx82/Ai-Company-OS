package com.aicompany.backend.persistence;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.catalog.CatalogBootstrap;
import com.aicompany.backend.deletion.AgentDeletionService;
import com.aicompany.backend.harness.service.AgentTemplates;
import com.aicompany.backend.support.PostgresTestcontainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 30 (ADR-035 §4): the initial set is reinstallable, so it is not
 * protected -- and a delete of it lasts. Deleting every agent of the development
 * seed, then running what a restart runs (the seed stream, the catalogs, the
 * template-origin pass), brings none of them back. Only an explicit install does,
 * once per name.
 *
 * <p>A context of its own (the dev profile and a distinct property), so the
 * database it empties is not the one {@link DevSeedMigrationTest} counts.
 */
@SpringBootTest(properties = {
        "AICOS_HOME=${java.io.tmpdir}/aicos-seed-deletion-test-home",
        "aicos.home=${java.io.tmpdir}/aicos-seed-deletion-test-home",
        "aicos.security.admin.password=seed-deletion-secret-42",
        "aicos.ecosystem.autostart=false",
        "aicos.launcher.enabled=false"})
@ActiveProfiles("dev")
@Import(PostgresTestcontainerConfig.class)
class SeedAgentDeletionTest {

    @Autowired
    private AgentRepository agents;

    @Autowired
    private AgentDeletionService deletions;

    @Autowired
    private AgentTemplates templates;

    @Autowired
    private CatalogBootstrap catalogs;

    @Autowired
    private DataSource dataSource;

    @Test
    void theWholeSeedSetIsDeletedForGoodAndOnlyAnExplicitInstallCreatesAgentsAgain() throws Exception {
        assertThat(agents.findAll()).extracting(Agent::getName)
                .containsExactlyInAnyOrder("Code Architect", "Frontend Developer", "Database Specialist");

        Map<String, String> promptsBefore = new java.util.HashMap<>();
        agents.findAll().forEach(a -> promptsBefore.put(a.getName(), a.getSystemPrompt()));

        Map<Long, Precondition> everyone = new LinkedHashMap<>();
        agents.findAll().forEach(a -> everyone.put(a.getId(), Precondition.fromHeader(ETags.of(a.getVersion()))));
        deletions.deleteAll(everyone, AgentDeletionService.SubAgents.DETACH, AgentDeletionService.InProgress.BLOCK, "test");
        assertThat(agents.count()).isZero();

        // What the next start runs, in its order.
        assertThat(DevSeedFlyway.apply(dataSource, null).migrationsExecuted).isZero();
        catalogs.run(null);
        templates.recordTemplateOrigins();
        assertThat(agents.count()).as("nothing comes back on its own").isZero();

        // The explicit reinstall: the preview says every agent would be created, and it is -- once.
        assertThat(templates.plan(null)).allMatch(p -> p.outcome() == AgentTemplates.Outcome.CREATED);
        int created = templates.installAll().size();
        assertThat(agents.count()).isEqualTo(created);
        assertThat(templates.installAll()).allMatch(i -> i.outcome() == AgentTemplates.Outcome.EXISTING);
        assertThat(agents.count()).as("a second install duplicates nothing").isEqualTo(created);

        // The initial set is among the templates: the three seed agents are back, configured as the seed made them.
        for (Map.Entry<String, String> seeded : promptsBefore.entrySet()) {
            Agent back = agents.findAll().stream().filter(a -> a.getName().equals(seeded.getKey())).findFirst().orElseThrow();
            assertThat(back.getSystemPrompt()).as(seeded.getKey()).isEqualTo(seeded.getValue());
            assertThat(back.getModel()).as(seeded.getKey()).isEqualTo("ollama:qwen3.5:9b");
        }
    }
}
