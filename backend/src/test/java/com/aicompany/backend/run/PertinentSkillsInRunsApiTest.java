package com.aicompany.backend.run;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 35 (ADR-041, phase 2; revises ADR-026 §3): an engine run carries at most two
 * skills or knowledge entries, those pertinent to the task, and of each only its
 * essential part. Everything stable -- role, profile, the whole harness by name, the
 * autonomy rules -- comes first and is the same for every task of the agent, so Ollama
 * can reuse that prefix from one run to the next; what changes comes after.
 */
class PertinentSkillsInRunsApiTest extends AbstractPostgresTest {

    private static final List<String> KEYS = List.of("zeta-sql", "zeta-react", "zeta-integration");
    private static final String MARKER = "\n\nRelevant to this task";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillLibrary library;

    @Autowired
    private HarnessService harness;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;

    private static String skill(String key, String name, String description, String tag, String body) {
        return "---\nkey: " + key + "\nname: " + name + "\nkind: SKILL\ndescription: " + description + "\ntags: [" + tag
                + "]\n---\n\n" + body;
    }

    @BeforeEach
    void anAgentWithThreeSkills() throws Exception {
        clean();
        engine.reset();
        for (String content : List.of(
                skill("zeta-sql", "Migrazioni SQL", "Vincoli e migrazioni additive.", "sql", """
                        # Migrazioni SQL

                        SQL-INTRO-NON-INVIATA.

                        ## Essenziale

                        - Ogni vincolo ha un nome.

                        ## Dettagli

                        DETTAGLI-NON-INVIATI.
                        """),
                skill("zeta-react", "Componenti React", "Hook e tipi.", "react", """
                        # Componenti React

                        REACT-INTRO.

                        ## Istruzioni

                        REACT-ISTRUZIONI-NON-INVIATE.
                        """),
                skill("zeta-integration", "Test di integrazione", "Testcontainers e dati reali.", "testing",
                        "INTEGRATION-INTRO.\n"))) {
            mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(json.createObjectNode().put("content", content))))
                    .andExpect(status().isCreated());
        }
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P35 Prefix Bot\",\"role\":\"Full Stack Engineer\",\"specialization\":\"web\"}")).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/agents/" + agentId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"systemPrompt\":\"Scrivi codice chiaro\"}"))
                .andExpect(status().isOk());
        for (String key : KEYS) {
            mockMvc.perform(put("/api/agents/" + agentId + "/resources/" + key)).andExpect(status().isNoContent());
        }
    }

    @AfterEach
    void clean() throws Exception {
        engine.release();
        jdbc.update("DELETE FROM task_runs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P35 %'");
        jdbc.update("DELETE FROM project_resources WHERE project_id IN (SELECT id FROM projects WHERE name = 'Prefisso P35')");
        jdbc.update("DELETE FROM projects WHERE name = 'Prefisso P35'");
        jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE name = 'P35 Prefix Bot')");
        jdbc.update("DELETE FROM agents WHERE name = 'P35 Prefix Bot'");
        for (String key : KEYS) {
            jdbc.update("DELETE FROM agent_resources WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
            jdbc.update("DELETE FROM harness_resources WHERE key = ?", key);
            Path entry = library.root().resolve("skills").resolve(key);
            if (Files.exists(entry)) {
                try (Stream<Path> walk = Files.walk(entry)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    @Test
    void onlyThePertinentSkillsGoInAndOnlyTheirEssentialPart() throws Exception {
        String sql = systemOf(task("P35 Scrivi la migrazione SQL dei prestiti"));
        assertThat(sql).as("the whole harness, by name, in the stable part")
                .contains("Your harness: skill Migrazioni SQL; skill Componenti React; skill Test di integrazione.");
        String chosen = sql.substring(sql.indexOf(MARKER));
        assertThat(chosen).contains("Skill \"Migrazioni SQL\"").contains("- Ogni vincolo ha un nome.")
                .doesNotContain("SQL-INTRO-NON-INVIATA").doesNotContain("DETTAGLI-NON-INVIATI")
                .doesNotContain("REACT").doesNotContain("INTEGRATION");

        String web = systemOf(task("P35 Componenti React e test di integrazione"));
        String webChosen = web.substring(web.indexOf(MARKER));
        assertThat(webChosen).contains("REACT-INTRO.").contains("INTEGRATION-INTRO.")
                .doesNotContain("REACT-ISTRUZIONI-NON-INVIATE").doesNotContain("Ogni vincolo");
    }

    @Test
    void theStablePartIsTheSameForEveryTaskOfTheAgentAndComesFirst() throws Exception {
        // A project that adopted an MCP server: its line belongs to the stable part too.
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Prefisso P35\"}")).andExpect(status().isCreated()).andReturn();
        long projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/resources/mcp-filesystem")).andExpect(status().isNoContent());

        String first = systemOf(task("P35 Scrivi la migrazione SQL dei prestiti", projectId));
        String second = systemOf(task("P35 Componenti React e test di integrazione", projectId));
        String none = systemOf(task("P35 Aggiorna il README", projectId));

        String prefix = first.substring(0, first.indexOf(MARKER));
        assertThat(second.substring(0, second.indexOf(MARKER))).isEqualTo(prefix);
        assertThat(prefix).startsWith("You are P35 Prefix Bot, Full Stack Engineer in AI Company OS.")
                .contains("Agent instructions:\nScrivi codice chiaro")
                .endsWith("Adopted by this project, for every agent working on it: mcp Filesystem MCP.");
        // Nothing pertinent: the run carries the stable part alone.
        assertThat(none).isEqualTo(prefix);
    }

    @Test
    void resourcesLinkedAtTheSameInstantKeepOneOrder() {
        Long sqlId = jdbc.queryForObject("SELECT id FROM harness_resources WHERE key = 'zeta-sql'", Long.class);
        Long reactId = jdbc.queryForObject("SELECT id FROM harness_resources WHERE key = 'zeta-react'", Long.class);
        jdbc.update("DELETE FROM agent_resources WHERE agent_id = ?", agentId);
        // Linked in one transaction, the higher id first: the order must not depend on how they were stored.
        jdbc.update("INSERT INTO agent_resources (agent_id, resource_id, created_at) VALUES (?, ?, TIMESTAMP '2026-11-08 10:00:00'), "
                + "(?, ?, TIMESTAMP '2026-11-08 10:00:00')", agentId, Math.max(sqlId, reactId), agentId, Math.min(sqlId, reactId));

        List<String> keys = harness.of(agentId).resources().stream().map(r -> r.getKey()).toList();
        assertThat(keys).containsExactly(sqlId < reactId ? "zeta-sql" : "zeta-react", sqlId < reactId ? "zeta-react" : "zeta-sql");
    }

    // ------------------------------------------------------------------ helpers

    private long task(String title) throws Exception {
        return task(title, null);
    }

    private long task(String title, Long projectId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"LOW\""
                        + (projectId == null ? "" : ",\"projectId\":" + projectId) + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        String tag = mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH,
                        created.getResponse().getHeader(HttpHeaders.ETAG)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":" + agentId + "}")).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(post("/api/tasks/" + id + "/runs").header(HttpHeaders.IF_MATCH, tag)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());
        return id;
    }

    private String systemOf(long taskId) throws Exception {
        EngineClient.Request request = null;
        for (int i = 0; i < 200 && request == null; i++) {
            request = engine.requests().stream().filter(r -> r.user().startsWith("Task #" + taskId + ":")).findFirst().orElse(null);
            if (request == null) {
                Thread.sleep(25);
            }
        }
        assertThat(request).as("the run of task " + taskId + " reached the engine").isNotNull();
        for (int i = 0; i < 200; i++) {
            String runs = mockMvc.perform(get("/api/tasks/" + taskId + "/runs")).andReturn().getResponse().getContentAsString();
            if (!runs.contains("QUEUED") && !runs.contains("RUNNING")) {
                break;
            }
            Thread.sleep(25);
        }
        return request.system();
    }
}
