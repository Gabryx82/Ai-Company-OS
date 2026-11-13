package com.aicompany.backend.orchestrator;

import com.aicompany.backend.harness.hub.SkillLocations;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
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
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 36 (ADR-042): {@code GET /api/tasks/{id}/readiness} says, for an agent and a
 * target, what the agent will find -- each item OK, a warning or blocking -- and the
 * handoff and the run stop on what it blocks, before anything is written or opened. A
 * skill without its file, or with a file the library cannot read, is a warning there and
 * a line in the package, never a silence; a skill missing from the tool's folder is said
 * before the tool opens, with the copy to make.
 */
class TaskReadinessApiTest extends AbstractPostgresTest {

    private static final List<String> KEYS = List.of("p36r-copied", "p36r-missing-copy", "p36r-no-file", "p36r-broken",
            "p36r-notes", "p36r-mcp", "p36r-mcp-manual", "p36r-tool");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillLibrary library;

    @Autowired
    private SkillLocations locations;

    @Autowired
    private FakeHost host;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;
    private long projectId;
    private Path workspace;

    private static String entry(String key, String kind, String name) {
        return "---\nkey: " + key + "\nname: " + name + "\nkind: " + kind + "\ndescription: " + name + ".\n---\n\n# "
                + name + "\n\n- Una regola.\n";
    }

    private void resource(String key, String kind, String name, String configuration) throws Exception {
        ObjectNode body = json.createObjectNode().put("key", key).put("kind", kind).put("name", name).put("description", name);
        if (configuration != null) {
            body.put("configuration", configuration);
        }
        mockMvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    @BeforeEach
    void anAgentWithOneResourceOfEveryState() throws Exception {
        clean();
        Files.createDirectories(locations.home().resolve(".claude").resolve("skills"));
        for (String content : List.of(entry("p36r-copied", "SKILL", "Readiness Copiata"),
                entry("p36r-missing-copy", "SKILL", "Readiness Da Copiare"),
                entry("p36r-broken", "SKILL", "Readiness Rotta"),
                entry("p36r-notes", "KNOWLEDGE", "Readiness Note"))) {
            mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(json.createObjectNode().put("content", content))))
                    .andExpect(status().isCreated());
        }
        // A file the library cannot read as its document: the frontmatter is gone.
        Files.writeString(library.root().resolve("skills/p36r-broken/SKILL.md"), "# Readiness Rotta\n\nSenza frontmatter.\n");
        // A skill of the catalog that never had a file.
        resource("p36r-no-file", "SKILL", "Readiness Senza File", null);
        resource("p36r-mcp", "MCP", "Readiness MCP", "npx -y @readiness/mcp");
        resource("p36r-mcp-manual", "MCP", "Readiness MCP a mano", "configura il server dall'app");
        resource("p36r-tool", "TOOL", "Readiness Tool", "tool --check");
        mockMvc.perform(post("/api/skill-hub/library/p36r-copied/copies/claude-code")).andExpect(status().isOk());

        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Readiness Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"SQL\"}"))
                .andExpect(status().isCreated()).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        for (String key : KEYS) {
            mockMvc.perform(put("/api/agents/" + agentId + "/resources/" + key)).andExpect(status().isNoContent());
        }
        mockMvc.perform(put("/api/agents/" + agentId + "/software/docker-desktop")).andExpect(status().isNoContent());
        host.script("docker-desktop", new Detection(Availability.NOT_INSTALLED, "non trovato", null));

        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Readiness P36\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        workspace = folder.resolve("readiness");
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"DELEGATED\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    @AfterEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P36R %')");
        jdbc.update("DELETE FROM task_runs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P36R %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P36R %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P36R %'");
        jdbc.update("DELETE FROM projects WHERE name = 'Readiness P36'");
        jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE name = 'Readiness Bot')");
        jdbc.update("DELETE FROM agent_software WHERE agent_id IN (SELECT id FROM agents WHERE name = 'Readiness Bot')");
        jdbc.update("DELETE FROM agents WHERE name = 'Readiness Bot'");
        for (String key : KEYS) {
            jdbc.update("DELETE FROM skill_installations WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
            jdbc.update("DELETE FROM agent_resources WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
            jdbc.update("DELETE FROM harness_resources WHERE key = ?", key);
            delete(library.root().resolve("skills").resolve(key));
            delete(library.root().resolve("knowledge").resolve(key + ".md"));
            delete(locations.home().resolve(".claude").resolve("skills").resolve(key));
        }
    }

    private static void delete(Path path) throws Exception {
        if (Files.exists(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private long task(String title) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":"
                                + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode readiness(long taskId, String target) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/tasks/" + taskId + "/readiness" + (target == null ? "" : "?target=" + target)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode item(JsonNode readiness, String area, String key) {
        for (JsonNode item : readiness.path("items")) {
            if (item.path("area").asString().equals(area) && item.path("key").asString().equals(key)) {
                return item;
            }
        }
        throw new AssertionError("no " + area + " item '" + key + "' in " + readiness.toPrettyString());
    }

    private static String state(JsonNode readiness, String area, String key) {
        return item(readiness, area, key).path("status").asString();
    }

    @Test
    void forClaudeCodeEveryResourceIsCheckedWhereTheToolReadsIt() throws Exception {
        long taskId = task("P36R Indici delle prestazioni");
        JsonNode r = readiness(taskId, "claude-code");

        assertThat(r.path("target").asString()).isEqualTo("claude-code");
        assertThat(r.path("ready").asBoolean()).as("warnings do not stop the delivery").isTrue();
        assertThat(state(r, "SKILL", "p36r-copied")).isEqualTo("OK");
        assertThat(item(r, "SKILL", "p36r-copied").path("detail").asString()).contains("Copia allineata in Claude Code");
        JsonNode missingCopy = item(r, "SKILL", "p36r-missing-copy");
        assertThat(missingCopy.path("status").asString()).isEqualTo("WARNING");
        assertThat(missingCopy.path("detail").asString()).contains("Non è nella cartella di Claude Code");
        assertThat(missingCopy.path("action").path("type").asString()).isEqualTo("HUB_COPY");
        assertThat(missingCopy.path("action").path("app").asString()).isEqualTo("claude-code");
        assertThat(state(r, "SKILL", "p36r-no-file")).isEqualTo("WARNING");
        assertThat(item(r, "SKILL", "p36r-no-file").path("detail").asString()).contains("Nessun file nella libreria");
        assertThat(state(r, "SKILL", "p36r-broken")).isEqualTo("WARNING");
        assertThat(item(r, "SKILL", "p36r-broken").path("detail").asString()).contains("non è un documento valido");
        assertThat(state(r, "KNOWLEDGE", "p36r-notes")).isEqualTo("OK");
        assertThat(state(r, "MCP", "p36r-mcp")).isEqualTo("OK");
        assertThat(item(r, "MCP", "p36r-mcp").path("detail").asString()).contains(".mcp.json");
        assertThat(state(r, "MCP", "p36r-mcp-manual")).isEqualTo("WARNING");
        assertThat(state(r, "TOOL", "p36r-tool")).isEqualTo("OK");
        assertThat(state(r, "SOFTWARE", "docker-desktop")).isEqualTo("WARNING");
        assertThat(state(r, "BINDING", "claude-code")).isEqualTo("OK");
        assertThat(state(r, "BINDING", "target")).as("the agent works in the engine").isEqualTo("WARNING");
        assertThat(state(r, "CONTEXT", "AGENTS.md")).isEqualTo("OK");
        assertThat(state(r, "CONTEXT", "CLAUDE.md")).isEqualTo("OK");

        // The copy the check proposes, made: the same skill is then in step.
        mockMvc.perform(post("/api/skill-hub/library/p36r-missing-copy/copies/claude-code")).andExpect(status().isOk());
        assertThat(state(readiness(taskId, "claude-code"), "SKILL", "p36r-missing-copy")).isEqualTo("OK");
    }

    @Test
    void theProblemsOfTheLibraryAreInThePackageTooNeverASilence() throws Exception {
        long taskId = task("P36R Vincoli dello schema");
        mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"manual\"}")).andExpect(status().isAccepted());
        String pkg = Files.readString(workspace.resolve(".aicos/handoffs/TASK-" + taskId + "-manual.md"));
        String noFile = pkg.lines().dropWhile(l -> !l.contains("Readiness Senza File")).limit(2).reduce("", String::concat);
        assertThat(noFile).contains("⚠ nessun file nella libreria");
        String broken = pkg.lines().dropWhile(l -> !l.contains("Readiness Rotta")).limit(2).reduce("", String::concat);
        assertThat(broken).contains("⚠ il file").contains("non è un documento valido della libreria");
    }

    @Test
    void forCodexAndTheEngineTheMcpServersAreToConfigureByHand() throws Exception {
        long taskId = task("P36R Report mensile");
        JsonNode codex = readiness(taskId, "codex");
        assertThat(state(codex, "MCP", "p36r-mcp")).isEqualTo("WARNING");
        assertThat(item(codex, "MCP", "p36r-mcp").path("detail").asString()).contains("Da configurare a mano in ChatGPT / Codex");

        JsonNode engine = readiness(taskId, null);
        assertThat(engine.path("target").asString()).as("without a target, the agent's own").isEqualTo("engine");
        assertThat(state(engine, "MCP", "p36r-mcp")).isEqualTo("WARNING");
        assertThat(state(engine, "SKILL", "p36r-copied")).isEqualTo("OK");
        assertThat(engine.path("ready").asBoolean()).isTrue();
    }

    @Test
    void aToolThatCannotOpenBlocksTheHandoffBeforeAnythingIsWritten() throws Exception {
        host.script("claude-code", new Detection(Availability.NOT_INSTALLED, "claude non è nel PATH", null));
        long taskId = task("P36R Migrazione bloccata");

        JsonNode r = readiness(taskId, "claude-code");
        assertThat(r.path("ready").asBoolean()).isFalse();
        assertThat(state(r, "BINDING", "claude-code")).isEqualTo("BLOCKING");

        mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:delivery-not-ready"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Claude Code (CLI) non si può aprire")));
        assertThat(workspace.resolve(".aicos/handoffs/TASK-" + taskId + "-claude-code.md")).doesNotExist();
        assertThat(json.readTree(mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getContentAsString())
                .path("status").asString()).as("the task did not start").isEqualTo("OPEN");
        assertThat(host.launches()).isEmpty();
    }

    @Test
    void withTheLauncherOffTheDeliveryIsPreparedWholeAndTheToolIsNotOpened() throws Exception {
        host.switchLauncherOff("The launcher is switched off on this control plane (aicos.launcher.enabled=false)");
        long taskId = task("P36R Consegna senza launcher");

        JsonNode r = readiness(taskId, "claude-code");
        assertThat(r.path("ready").asBoolean()).as("the package can still be prepared").isTrue();
        assertThat(state(r, "BINDING", "launcher")).isEqualTo("WARNING");
        assertThat(item(r, "BINDING", "launcher").path("detail").asString()).contains("non apre Claude Code (CLI)");

        JsonNode result = json.readTree(mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs")
                        .header(HttpHeaders.IF_MATCH, tag(taskId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        assertThat(workspace.resolve(".aicos/handoffs/TASK-" + taskId + "-claude-code.md")).exists();
        assertThat(result.path("notOpened").asString()).contains("switched off");
        assertThat(result.path("promptToClipboard").asBoolean()).as("the operator pastes it").isTrue();
        assertThat(result.path("handoff").path("command").asString()).as("what it would have run").contains("claude");
        assertThat(result.path("task").path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(host.launches()).as("nothing opened").isEmpty();
    }

    @Test
    void aTaskWithoutAgentIsBlockedAndAnAgentThatWorksElsewhereBlocksTheRun() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"P36R Senza agente\",\"status\":\"OPEN\",\"priority\":\"LOW\",\"projectId\":"
                                + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long alone = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        JsonNode unassigned = readiness(alone, "claude-code");
        assertThat(unassigned.path("ready").asBoolean()).isFalse();
        assertThat(state(unassigned, "TASK", "task")).isEqualTo("BLOCKING");

        String etag = mockMvc.perform(get("/api/agents/" + agentId + "/configuration")).andReturn().getResponse()
                .getHeader(HttpHeaders.ETAG);
        mockMvc.perform(put("/api/agents/" + agentId + "/configuration").header(HttpHeaders.IF_MATCH, etag)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"Backend Engineer\",\"specialization\":\"SQL\",\"executionTarget\":\"claude-code\"}"))
                .andExpect(status().isOk());
        long taskId = task("P36R Run altrove");
        JsonNode run = readiness(taskId, "engine");
        assertThat(run.path("ready").asBoolean()).isFalse();
        assertThat(item(run, "BINDING", "engine").path("detail").asString()).contains("L'agente lavora con Claude Code (CLI)");
        assertThat(readiness(taskId, null).path("target").asString()).as("the agent's own target").isEqualTo("claude-code");
    }
}
