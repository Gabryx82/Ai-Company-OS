package com.aicompany.backend.orchestrator;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import com.aicompany.backend.run.engine.EngineClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
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
 * PHASE 34: what a project adopted -- skills, knowledge, MCP -- reaches every agent that
 * works on it, in the handoff package and in an engine run, after the agent's own and
 * without repeating what the agent already carries.
 */
class ProjectResourcesApiTest extends AbstractPostgresTest {

    private static final List<String> SKILLS = List.of("p34-agent-rules", "p34-project-naming");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillLibrary library;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;
    private long projectId;

    private static String skill(String key, String name, String rule) {
        return """
                ---
                key: %s
                name: %s
                kind: SKILL
                description: Regole di %s.
                ---

                # %s

                - %s
                """.formatted(key, name, name, name, rule);
    }

    @BeforeEach
    void aProjectThatAdoptedResources() throws Exception {
        clean();
        for (String content : List.of(skill("p34-agent-rules", "Regole dell'agente", "Sempre un test per ogni endpoint."),
                skill("p34-project-naming", "Nomi del progetto", "I nomi delle tabelle sono al plurale."))) {
            mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(json.createObjectNode().put("content", content))))
                    .andExpect(status().isCreated());
        }
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Adoption Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"SQL\"}")).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/agents/" + agentId + "/resources/p34-agent-rules")).andExpect(status().isNoContent());

        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Biblioteca P34\"}")).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"DELEGATED\",\"workspacePath\":\""
                        + folder.resolve("biblioteca").toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        // The agent's own skill adopted again, a new one, and an MCP server.
        for (String key : List.of("p34-agent-rules", "p34-project-naming", "mcp-filesystem")) {
            mockMvc.perform(put("/api/projects/" + projectId + "/resources/" + key)).andExpect(status().isNoContent());
        }
    }

    @AfterEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P34 %')");
        jdbc.update("DELETE FROM task_runs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P34 %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P34 %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P34 %'");
        jdbc.update("DELETE FROM project_resources WHERE project_id IN (SELECT id FROM projects WHERE name = 'Biblioteca P34')");
        jdbc.update("DELETE FROM projects WHERE name = 'Biblioteca P34'");
        jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE name = 'Adoption Bot')");
        jdbc.update("DELETE FROM agents WHERE name = 'Adoption Bot'");
        for (String key : SKILLS) {
            jdbc.update("DELETE FROM project_resources WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
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

    private long task(String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":" + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    @Test
    void theHandoffPackageListsWhatTheProjectAdoptedAfterTheAgentsOwn() throws Exception {
        long taskId = task("P34 Schema dei prestiti");

        MvcResult result = mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isAccepted()).andReturn();
        JsonNode handoff = json.readTree(result.getResponse().getContentAsString());

        Path biblioteca = folder.resolve("biblioteca");
        String pkg = Files.readString(biblioteca.resolve(".aicos/handoffs/TASK-" + taskId + "-claude-code.md"));
        String section = pkg.substring(pkg.indexOf("## Skill, knowledge, tool e MCP"), pkg.indexOf("## Contesto da leggere"));
        assertThat(section).as("the agent's own skill, once").containsOnlyOnce("**SKILL** Regole dell'agente");
        String adopted = section.substring(section.indexOf("**Adottate dal progetto**"));
        assertThat(adopted).contains("**SKILL** Nomi del progetto")
                .contains(library.root().resolve("skills/p34-project-naming/SKILL.md").toString())
                .contains("**MCP** Filesystem MCP")
                .doesNotContain("**SKILL** Regole dell'agente");
        // The adopted MCP server is configured where Claude Code reads it.
        assertThat(handoff.path("written").toString()).contains(".mcp.json");
        assertThat(json.readTree(Files.readString(biblioteca.resolve(".mcp.json"))).path("mcpServers").has("mcp-filesystem")).isTrue();
        assertThat(handoff.path("fullPrompt").asString()).contains("Nomi del progetto");
    }

    @Test
    void anEngineRunGetsTheAdoptedSkillsAsInstructionsWithoutRepeatingTheAgentsOwn() throws Exception {
        long taskId = task("P34 Indici del catalogo");

        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        EngineClient.Request request = null;
        for (int i = 0; i < 100 && request == null; i++) {
            request = engine.requests().stream().filter(r -> r.system().contains("Adoption Bot")).findFirst().orElse(null);
            if (request == null) {
                Thread.sleep(50);
            }
        }
        assertThat(request).isNotNull();
        assertThat(request.system())
                .contains("Adopted by this project, for every agent working on it: skill Nomi del progetto; mcp Filesystem MCP.")
                .contains("I nomi delle tabelle sono al plurale.")
                .containsOnlyOnce("Sempre un test per ogni endpoint.");

        // Let the run finish before the cleanup removes its task.
        for (int i = 0; i < 100; i++) {
            String runs = mockMvc.perform(get("/api/tasks/" + taskId + "/runs")).andReturn().getResponse().getContentAsString();
            if (!runs.contains("QUEUED") && !runs.contains("RUNNING")) {
                break;
            }
            Thread.sleep(50);
        }
    }
}
