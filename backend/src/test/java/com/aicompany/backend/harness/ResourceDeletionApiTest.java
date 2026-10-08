package com.aicompany.backend.harness;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 30 (ADR-035 §7): a skill or knowledge entry the operator created or
 * imported is deleted from the console with its file; the agents and projects
 * that used it lose the link and stay. An entry of the shipped catalog is
 * refused, with the reason: the next start would put it back.
 */
class ResourceDeletionApiTest extends AbstractPostgresTest {

    private static final String SKILL = """
            ---
            key: throwaway-skill
            name: Skill da buttare
            kind: SKILL
            description: Creata per essere eliminata.
            tags: [test]
            ---

            # Skill da buttare
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SkillLibrary library;

    @Autowired
    private JdbcTemplate jdbc;

    private String admin;

    @BeforeEach
    @AfterEach
    void aCleanLibrary() throws Exception {
        jdbc.update("UPDATE app_users SET failed_attempts = 0, locked_until = NULL");
        jdbc.update("DELETE FROM agent_resources");
        jdbc.update("DELETE FROM project_resources");
        jdbc.update("DELETE FROM harness_resources WHERE origin <> 'CATALOG'");
        jdbc.update("UPDATE harness_resources SET file_path = NULL, file_synced_at = NULL");
        jdbc.update("DELETE FROM agents WHERE name = 'Resource Holder'");
        jdbc.update("DELETE FROM projects WHERE name = 'Resource Project'");
        if (Files.exists(library.root())) {
            try (Stream<Path> walk = Files.walk(library.root())) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private String tagOf(String key) {
        return "\"" + jdbc.queryForObject("SELECT version FROM harness_resources WHERE key = ?", Long.class, key) + "\"";
    }

    @Test
    void anEntryTheOperatorCreatedGoesWithItsFileAndItsLinksButTheAgentAndProjectStay() throws Exception {
        admin = AdminSession.bearer(mockMvc);
        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(SKILL) + "}"))
                .andExpect(status().isCreated());
        Path file = library.root().resolve("skills/throwaway-skill/SKILL.md");
        Files.writeString(file.resolveSibling("example.txt"), "kept");
        assertThat(file).exists();
        long agent = jdbc.queryForObject("INSERT INTO agents (name, role, specialization, status, created_at, updated_at) "
                + "VALUES ('Resource Holder', 'r', 's', 'ACTIVE', now(), now()) RETURNING id", Long.class);
        long project = jdbc.queryForObject("INSERT INTO projects (name, status, created_at, updated_at) "
                + "VALUES ('Resource Project', 'ACTIVE', now(), now()) RETURNING id", Long.class);
        mockMvc.perform(put("/api/agents/" + agent + "/resources/throwaway-skill")).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/projects/" + project + "/resources/throwaway-skill")).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/resources/throwaway-skill/deletion"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.files[0]").value("skills/throwaway-skill/SKILL.md"))
                .andExpect(jsonPath("$.detaches[0]").value(org.hamcrest.Matchers.containsString("Resource Holder")))
                .andExpect(jsonPath("$.detaches[1]").value(org.hamcrest.Matchers.containsString("Resource Project")));

        mockMvc.perform(delete("/api/resources/throwaway-skill").header(HttpHeaders.IF_MATCH, tagOf("throwaway-skill")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/resources/throwaway-skill").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(delete("/api/resources/throwaway-skill").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tagOf("throwaway-skill")))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM harness_resources WHERE key = 'throwaway-skill'", Integer.class)).isZero();
        assertThat(file).doesNotExist();
        assertThat(file.resolveSibling("example.txt")).as("the operator's other files stay").exists();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agents WHERE id = ?", Integer.class, agent)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE id = ?", Integer.class, project)).isOne();

        // Re-reading the folder does not bring it back: the file is gone, not only the row.
        mockMvc.perform(post("/api/library/sync")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM harness_resources WHERE key = 'throwaway-skill'", Integer.class)).isZero();
    }

    @Test
    void anEntryWhoseFileIsAlreadyGoneIsStillDeleted() throws Exception {
        admin = AdminSession.bearer(mockMvc);
        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":" + tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(SKILL) + "}"))
                .andExpect(status().isCreated());
        Files.delete(library.root().resolve("skills/throwaway-skill/SKILL.md"));

        mockMvc.perform(get("/api/resources/throwaway-skill/deletion")).andExpect(jsonPath("$.files").isEmpty());
        mockMvc.perform(delete("/api/resources/throwaway-skill").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, tagOf("throwaway-skill")))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM harness_resources WHERE key = 'throwaway-skill'", Integer.class)).isZero();
    }

    @Test
    void anEntryOfTheShippedCatalogSaysItIsRetiredAndAnUnknownOneIsA404() throws Exception {
        admin = AdminSession.bearer(mockMvc);
        // PHASE 32 (TD-71): no longer refused -- retired; CatalogRetirementApiTest follows it to the restart and back.
        mockMvc.perform(get("/api/resources/clean-code-java/deletion"))
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.deletes").value(org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("congedata"))));

        mockMvc.perform(delete("/api/resources/no-such-entry").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound());
    }
}
