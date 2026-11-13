package com.aicompany.backend.orchestrator;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.support.AbstractPostgresTest;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 36 (the harness guarantee): every field of the agent's page, changed from the
 * API, is found again in the database, in what the API reads back, in the handoff
 * package of every target the operator delivers to -- Claude Code (CLI and desktop
 * app), Codex, OpenCode, Antigravity (agent manager and IDE), and the manual package
 * -- and in the prompt an engine run records. A field that is saved but does not
 * reach the agent is a defect: each assertion names the hop where it stopped.
 */
class HarnessChainApiTest extends AbstractPostgresTest {

    /** The handoff targets the operator uses, and the manual package. */
    static final List<String> TARGETS = List.of("claude-code", "claude-desktop", "codex", "opencode", "antigravity",
            "antigravity-ide", "manual");

    /** Words that no harness entry of this test shares: nothing is pertinent by chance. */
    private static final String TASK_TITLE = "Chain Rilascio trimestrale del modulo";

    private static final List<String> LIBRARY = List.of("p36-skill", "p36-knowledge");
    private static final List<String> CATALOG = List.of("p36-tool", "p36-framework", "p36-mcp");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillLibrary library;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;
    private long projectId;
    private Path workspace;

    // --- the fields of the page -----------------------------------------------------------

    /**
     * A text field of the page: its name in the request and response, its column, and
     * whether the response gives it as lines.
     */
    record TextField(String field, String column, boolean lines) {
        @Override
        public String toString() {
            return field;
        }
    }

    static Stream<TextField> textFields() {
        return Stream.of(
                new TextField("name", "name", false),
                new TextField("role", "role", false),
                new TextField("specialization", "specialization", false),
                new TextField("description", "description", false),
                new TextField("capabilities", "capabilities", true),
                new TextField("domain", "domain", false),
                new TextField("systemPrompt", "system_prompt", false),
                new TextField("responsibilities", "responsibilities", false),
                new TextField("directives", "directives", true),
                new TextField("limits", "limits", false),
                new TextField("outputFormat", "output_format", false),
                new TextField("contextPolicy", "context_policy", false));
    }

    // --- setup -----------------------------------------------------------------------------

    private static String libraryEntry(String key, String kind, String name, String description, String body) {
        return "---\nkey: " + key + "\nname: " + name + "\nkind: " + kind + "\ndescription: " + description
                + "\n---\n\n# " + name + "\n\n" + body + "\n";
    }

    @BeforeEach
    void anAgentAndAProjectWithAFolder() throws Exception {
        clean();
        for (String content : List.of(
                libraryEntry("p36-skill", "SKILL", "Catena Vincoli", "Vincoli con un nome esplicito.",
                        "- Ogni vincolo ha un nome esplicito."),
                libraryEntry("p36-knowledge", "KNOWLEDGE", "Catena Glossario", "Il glossario del dominio.",
                        "- Un prestito ha sempre una scadenza."))) {
            mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(json.createObjectNode().put("content", content))))
                    .andExpect(status().isCreated());
        }
        catalogEntry("p36-tool", "TOOL", "Catena Profiler", "Profila le query lente.", "profiler --all");
        catalogEntry("p36-framework", "FRAMEWORK", "Catena Framework", "Il framework dei servizi.", null);
        catalogEntry("p36-mcp", "MCP", "Catena MCP", "Un server MCP di prova.", "npx -y @catena/mcp-server");

        agentId = agent("Chain Bot");

        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Catena P36\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        workspace = folder.resolve("catena");
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"DELEGATED\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    @AfterEach
    void clean() throws Exception {
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'Chain %')");
        jdbc.update("DELETE FROM task_runs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'Chain %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'Chain %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'Chain %'");
        jdbc.update("DELETE FROM project_resources WHERE project_id IN (SELECT id FROM projects WHERE name = 'Catena P36')");
        jdbc.update("DELETE FROM projects WHERE name = 'Catena P36'");
        jdbc.update("UPDATE agents SET parent_id = NULL WHERE name LIKE 'Chain Bot%'");
        jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE name LIKE 'Chain Bot%')");
        jdbc.update("DELETE FROM agent_software WHERE agent_id IN (SELECT id FROM agents WHERE name LIKE 'Chain Bot%')");
        jdbc.update("DELETE FROM agents WHERE name LIKE 'Chain Bot%'");
        for (String key : Stream.concat(LIBRARY.stream(), CATALOG.stream()).toList()) {
            jdbc.update("DELETE FROM project_resources WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
            jdbc.update("DELETE FROM agent_resources WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", key);
            jdbc.update("DELETE FROM harness_resources WHERE key = ?", key);
        }
        for (Path entry : List.of(library.root().resolve("skills").resolve("p36-skill"),
                library.root().resolve("knowledge").resolve("p36-knowledge.md"))) {
            if (Files.exists(entry)) {
                try (Stream<Path> walk = Files.walk(entry)) {
                    walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    private void catalogEntry(String key, String kind, String name, String description, String configuration) throws Exception {
        ObjectNode body = json.createObjectNode().put("key", key).put("kind", kind).put("name", name)
                .put("description", description);
        if (configuration != null) {
            body.put("configuration", configuration);
        }
        mockMvc.perform(post("/api/resources").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isCreated());
    }

    private long agent(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"role\":\"Backend Engineer\",\"specialization\":\"SQL\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    // --- the hops ----------------------------------------------------------------------------

    /** The whole page, as the console sends it, with these fields changed. */
    private JsonNode save(Map<String, Object> changed) throws Exception {
        MvcResult read = mockMvc.perform(get("/api/agents/" + agentId + "/configuration")).andExpect(status().isOk()).andReturn();
        ObjectNode body = json.createObjectNode().put("name", "Chain Bot").put("role", "Backend Engineer")
                .put("specialization", "SQL").put("executionTarget", "engine");
        changed.forEach((field, value) -> {
            if (value == null) {
                body.putNull(field);
            } else if (value instanceof Long number) {
                body.put(field, number);
            } else {
                body.put(field, value.toString());
            }
        });
        MvcResult saved = mockMvc.perform(put("/api/agents/" + agentId + "/configuration")
                        .header(HttpHeaders.IF_MATCH, read.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(saved.getResponse().getContentAsString());
    }

    /** The configuration read back, as the page reads it when it opens. */
    private JsonNode reread() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/agents/" + agentId + "/configuration")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private long task() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + TASK_TITLE + "\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":"
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

    /** The package written for each target, keyed by target. */
    private Map<String, String> packages(long taskId) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (String target : TARGETS) {
            mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"" + target + "\"}"))
                    .andExpect(status().isAccepted());
            out.put(target, Files.readString(workspace.resolve(".aicos/handoffs/TASK-" + taskId + "-" + target + ".md")));
        }
        return out;
    }

    /** The run as recorded when it is launched: its model and its two prompts. */
    private JsonNode run(long taskId) throws Exception {
        MvcResult launched = mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted()).andReturn();
        JsonNode run = json.readTree(launched.getResponse().getContentAsString());
        awaitSettled(taskId);
        return run;
    }

    /** Lets the run finish before the cleanup removes its task. */
    private void awaitSettled(long taskId) throws Exception {
        for (int i = 0; i < 200; i++) {
            String runs = mockMvc.perform(get("/api/tasks/" + taskId + "/runs")).andReturn().getResponse().getContentAsString();
            if (!runs.contains("\"QUEUED\"") && !runs.contains("\"RUNNING\"")) {
                return;
            }
            Thread.sleep(25);
        }
    }

    private static boolean has(JsonNode node, String marker) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (item.asString().contains(marker)) {
                    return true;
                }
            }
            return false;
        }
        return !node.isNull() && !node.isMissingNode() && node.asString().contains(marker);
    }

    // --- the tests ----------------------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("textFields")
    void aTextFieldOfThePageReachesThePackageOfEveryTargetAndTheRun(TextField f) throws Exception {
        String marker = "Marcatore " + f.field() + " " + UUID.randomUUID().toString().substring(0, 8);
        String value = f.field().equals("name") ? "Chain Bot " + marker : marker;

        JsonNode saved = save(Map.of(f.field(), value));
        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(has(saved.path(f.field()), marker)).as("%s: the PUT answers with it", f).isTrue();
        String column = jdbc.queryForObject("SELECT " + f.column() + " FROM agents WHERE id = ?", String.class, agentId);
        soft.assertThat(column).as("%s: the database column %s holds it", f, f.column()).contains(marker);
        soft.assertThat(has(reread().path(f.field()), marker)).as("%s: the page reads it back", f).isTrue();

        long taskId = task();
        packages(taskId).forEach((target, text) ->
                soft.assertThat(text).as("%s: the handoff package for %s carries it", f, target).contains(marker));
        JsonNode run = run(taskId);
        soft.assertThat(run.path("systemPrompt").asString() + run.path("userPrompt").asString())
                .as("%s: the prompt of the engine run carries it", f).contains(marker);
        soft.assertAll();
    }

    @Test
    void theParentOfASubAgentReachesThePackageAndTheRun() throws Exception {
        long parent = agent("Chain Bot Capo");
        save(Map.of("parentId", parent));

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(jdbc.queryForObject("SELECT parent_id FROM agents WHERE id = ?", Long.class, agentId))
                .as("parentId: the database holds it").isEqualTo(parent);
        soft.assertThat(reread().path("parent").path("id").asLong()).as("parentId: the page reads it back").isEqualTo(parent);
        long taskId = task();
        packages(taskId).forEach((target, text) ->
                soft.assertThat(text).as("parentId: the package for %s names the parent", target).contains("Chain Bot Capo"));
        soft.assertThat(run(taskId).path("systemPrompt").asString()).as("parentId: the run names the parent")
                .contains("Chain Bot Capo");
        soft.assertAll();
    }

    /** A harness link of the page: the route that saves it, the name the agent should read. */
    static Stream<Arguments> links() {
        return Stream.of(
                Arguments.of("SKILL", "resources", "p36-skill", "Catena Vincoli"),
                Arguments.of("KNOWLEDGE", "resources", "p36-knowledge", "Catena Glossario"),
                Arguments.of("TOOL", "resources", "p36-tool", "Catena Profiler"),
                Arguments.of("FRAMEWORK", "resources", "p36-framework", "Catena Framework"),
                Arguments.of("MCP", "resources", "p36-mcp", "Catena MCP"),
                Arguments.of("SOFTWARE", "software", "docker-desktop", "Docker Desktop"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("links")
    void anEntryCheckedOnThePageReachesThePackageOfEveryTargetAndTheRun(String kind, String route, String key, String name)
            throws Exception {
        mockMvc.perform(put("/api/agents/" + agentId + "/" + route + "/" + key)).andExpect(status().isNoContent());

        SoftAssertions soft = new SoftAssertions();
        Integer rows = route.equals("software")
                ? jdbc.queryForObject("SELECT count(*) FROM agent_software l JOIN software s ON s.id = l.software_id "
                + "WHERE l.agent_id = ? AND s.key = ?", Integer.class, agentId, key)
                : jdbc.queryForObject("SELECT count(*) FROM agent_resources l JOIN harness_resources r ON r.id = l.resource_id "
                + "WHERE l.agent_id = ? AND r.key = ?", Integer.class, agentId, key);
        soft.assertThat(rows).as("%s: the database links it", kind).isEqualTo(1);
        JsonNode reread = reread();
        JsonNode listed = route.equals("software") ? reread.path("software") : reread.path("resources").path(kind);
        soft.assertThat(listed.toString()).as("%s: the page reads it back", kind).contains("\"" + key + "\"");

        long taskId = task();
        packages(taskId).forEach((target, text) ->
                soft.assertThat(text).as("%s: the package for %s names it", kind, target).contains(name));
        soft.assertThat(run(taskId).path("systemPrompt").asString()).as("%s: the run names it", kind).contains(name);
        soft.assertAll();
    }

    @Test
    void theModelReachesThePackageOfTheTargetsThatRunItAndTheRun() throws Exception {
        save(Map.of("model", "ollama:qwen3.5:4b"));

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(jdbc.queryForObject("SELECT model FROM agents WHERE id = ?", String.class, agentId))
                .as("model: the database holds it").isEqualTo("ollama:qwen3.5:4b");
        soft.assertThat(reread().path("model").asString()).as("model: the page reads it back").isEqualTo("ollama:qwen3.5:4b");
        long taskId = task();
        Map<String, String> packages = packages(taskId);
        // OpenCode runs Ollama models: the package names the agent's.
        soft.assertThat(packages.get("opencode")).as("model: the package for opencode names it").contains("`ollama:qwen3.5:4b`");
        // The others cannot run it: the package does not tell them to.
        for (String target : List.of("claude-code", "claude-desktop", "codex", "antigravity", "antigravity-ide")) {
            soft.assertThat(packages.get(target)).as("model: the package for %s does not claim a model it cannot run", target)
                    .doesNotContain("ollama:qwen3.5:4b");
        }
        soft.assertThat(run(taskId).path("requestedModel").asString()).as("model: the run uses it").isEqualTo("ollama:qwen3.5:4b");
        soft.assertAll();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"claude-code", "claude-desktop", "codex", "opencode", "antigravity", "antigravity-ide"})
    void theExecutionTargetIsTheDeliveryTheOrchestratorRecommendsAndARunIsRefused(String target) throws Exception {
        save(Map.of("executionTarget", target));

        SoftAssertions soft = new SoftAssertions();
        soft.assertThat(jdbc.queryForObject("SELECT execution_target FROM agents WHERE id = ?", String.class, agentId))
                .as("executionTarget: the database holds it").isEqualTo(target);
        soft.assertThat(reread().path("executionTarget").asString()).as("executionTarget: the page reads it back")
                .isEqualTo(target);
        long taskId = task();
        JsonNode decision = json.readTree(mockMvc.perform(get("/api/tasks/" + taskId + "/orchestration"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        List<String> recommended = decision.path("targets").valueStream().filter(t -> t.path("recommended").asBoolean())
                .map(t -> t.path("key").asString()).toList();
        soft.assertThat(recommended).as("executionTarget: the orchestrator recommends it, and only it").containsExactly(target);
        String pkg = packages(taskId).get(target);
        String name = decision.path("targets").valueStream().filter(t -> t.path("key").asString().equals(target))
                .findFirst().orElseThrow().path("name").asString();
        soft.assertThat(pkg).as("executionTarget: its package names it").contains("**Execution target**: " + name);
        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isConflict());
        soft.assertAll();
    }

    @Test
    void theMcpServersAreWrittenWhereEachToolReadsThemOrSaidToBeConfiguredByHand() throws Exception {
        mockMvc.perform(put("/api/agents/" + agentId + "/resources/p36-mcp")).andExpect(status().isNoContent());
        long taskId = task();
        Map<String, String> packages = packages(taskId);

        SoftAssertions soft = new SoftAssertions();
        // Claude Code reads the project's .mcp.json, in the terminal and in the desktop app alike.
        soft.assertThat(json.readTree(Files.readString(workspace.resolve(".mcp.json"))).path("mcpServers").has("p36-mcp"))
                .as("MCP: .mcp.json configures it").isTrue();
        for (String target : List.of("claude-code", "claude-desktop")) {
            soft.assertThat(packages.get(target)).as("MCP: the package for %s points to .mcp.json", target)
                    .contains("in `.mcp.json`: p36-mcp");
        }
        soft.assertThat(packages.get("opencode")).as("MCP: the package for opencode points to opencode.json")
                .contains("in `opencode.json`: p36-mcp");
        for (String target : List.of("codex", "antigravity", "antigravity-ide")) {
            soft.assertThat(packages.get(target)).as("MCP: the package for %s says to configure it by hand", target)
                    .contains("Da configurare a mano in").contains("Catena MCP");
        }
        soft.assertAll();
    }

    @Test
    void whatTheProjectAdoptedReachesEveryTargetAndTheRunOnceEach() throws Exception {
        mockMvc.perform(put("/api/agents/" + agentId + "/resources/p36-skill")).andExpect(status().isNoContent());
        for (String key : List.of("p36-skill", "p36-knowledge", "p36-tool", "p36-mcp")) {
            mockMvc.perform(put("/api/projects/" + projectId + "/resources/" + key)).andExpect(status().isNoContent());
        }
        long taskId = task();

        SoftAssertions soft = new SoftAssertions();
        packages(taskId).forEach((target, text) -> {
            String section = text.substring(text.indexOf("## Skill, knowledge, tool e MCP"), text.indexOf("## Contesto da leggere"));
            // One item per entry: the instruction under an item may name the skill again.
            soft.assertThat(section).as("adopted: the package for %s lists the agent's own skill once", target)
                    .containsOnlyOnce("**SKILL** Catena Vincoli");
            for (String item : List.of("**KNOWLEDGE** Catena Glossario", "**TOOL** Catena Profiler", "**MCP** Catena MCP")) {
                soft.assertThat(section).as("adopted: the package for %s lists %s", target, item).containsOnlyOnce(item);
            }
        });
        String system = run(taskId).path("systemPrompt").asString();
        soft.assertThat(system).as("adopted: the run names them")
                .contains("Catena Glossario").contains("Catena Profiler").contains("Catena MCP");
        soft.assertThat(system.split("Catena Vincoli", -1).length - 1).as("adopted: the run names the agent's own skill once")
                .isEqualTo(1);
        soft.assertAll();
    }
}
