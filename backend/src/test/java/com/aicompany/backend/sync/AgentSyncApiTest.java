package com.aicompany.backend.sync;

import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.harness.hub.SkillLocations;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import com.aicompany.backend.support.FakeHubCommands;
import com.aicompany.backend.support.FakeSyncHttp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 37 (ADR-043): one ecosystem. AI Company OS is where agents are made; the API
 * says how each stands in OpenClaw, Open WebUI and LiteLLM, brings the
 * tools up to date when an administrator asks, and brings in the agents OpenClaw
 * already had -- which stay OpenClaw's.
 *
 * <p>The tools here are a scripted {@code openclaw} command, a scripted Open WebUI and
 * a real LiteLLM file in a temporary folder: nothing of the developer's machine.
 */
class AgentSyncApiTest extends AbstractPostgresTest {

    private static final String LITELLM = """
            model_list:
              - model_name: orchestrator
                litellm_params:
                  model: ollama_chat/gemma4:e4b
                  api_base: http://host.docker.internal:11434

            general_settings:
              master_key: os.environ/LITELLM_MASTER_KEY
            """;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SkillLocations locations;

    @Autowired
    private FakeHubCommands cli;

    @Autowired
    private FakeSyncHttp http;

    @Autowired
    private OpenClawConnector openClaw;

    @Value("${aicos.sync.litellm.config}")
    private String litellmConfig;

    private final JsonMapper json = JsonMapper.builder().build();
    /** The agents the scripted OpenClaw has: id to workspace. */
    private final Map<String, Path> openClawAgents = new LinkedHashMap<>();
    /** The models the scripted Open WebUI has: id to the form it was given. */
    private final Map<String, JsonNode> webUiModels = new LinkedHashMap<>();
    private Path openClawState;
    private Path litellmFile;
    private String admin;
    private long frontend;
    private long architect;

    @BeforeEach
    void twoAgentsAndThreeToolsThatAnswer() throws Exception {
        clean();
        admin = AdminSession.bearer(mockMvc);

        // OpenClaw: configured, with a coordinator of its own.
        openClawState = Files.createDirectories(locations.home().resolve(".openclaw"));
        Files.writeString(openClawState.resolve("openclaw.json"), "{}");
        Path coordinator = Files.createDirectories(openClawState.resolve("workspace").resolve("coordinator"));
        Files.writeString(coordinator.resolve("AGENTS.md"), "# Chief of staff operating program\n\nOwn the outcome.\n");
        openClawAgents.put("coordinator", coordinator);
        relist();
        cli.onRun(call -> {
            List<String> a = call.arguments();
            if (call.tool() != Tool.OPENCLAW || a.size() < 3) {
                return;
            }
            try {
                if (a.subList(0, 2).equals(List.of("agents", "add"))) {
                    Path workspace = Files.createDirectories(Path.of(a.get(4)));
                    Files.writeString(workspace.resolve("AGENTS.md"), "# Default OpenClaw program\n");
                    openClawAgents.put(a.get(2), workspace);
                    cli.answerFirst(Tool.OPENCLAW, List.of("agents", "add", a.get(2)), 0, "{\"agentId\":\"" + a.get(2)
                            + "\",\"workspace\":" + json.writeValueAsString(workspace.toString()) + "}");
                    relist();
                } else if (a.subList(0, 2).equals(List.of("agents", "delete"))) {
                    openClawAgents.remove(a.get(2));
                    relist();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });

        // Open WebUI: up, with the key accepted, and no model of ours yet.
        http.answer("GET", "/health", 200, "{\"status\":true}");
        http.answer("GET", "/api/v1/models/list", call -> {
            ArrayNode items = json.createArrayNode();
            if (call.path().endsWith("page=1")) {
                webUiModels.values().forEach(items::add);
            }
            return new FakeSyncHttp.Response(200, json.writeValueAsString(json.createObjectNode().set("items", items)));
        });
        http.answer("GET", "/api/v1/models/model", call -> {
            String id = java.net.URLDecoder.decode(call.uri().getRawQuery().substring("id=".length()), java.nio.charset.StandardCharsets.UTF_8);
            return new FakeSyncHttp.Response(200, webUiModels.containsKey(id) ? json.writeValueAsString(webUiModels.get(id)) : "null");
        });
        http.answer("POST", "/api/v1/models/create", call -> store(call.json()));
        http.answer("POST", "/api/v1/models/model/update", call -> store(call.json()));
        http.answer("POST", "/api/v1/models/model/delete", call -> {
            webUiModels.remove(json.readTree(call.json()).path("id").asString());
            return new FakeSyncHttp.Response(200, "true");
        });

        // LiteLLM: a gateway file.
        litellmFile = Path.of(litellmConfig);
        Files.createDirectories(litellmFile.getParent());
        Files.writeString(litellmFile, LITELLM);

        frontend = agent("P37 Frontend", "Sviluppatore frontend", "React e TypeScript", "\"ollama:qwen3.5:9b\"");
        architect = agent("P37 Architect", "Software Architect", "Architettura", "null");
    }

    private FakeSyncHttp.Response store(String form) {
        JsonNode model = json.readTree(form);
        webUiModels.put(model.path("id").asString(), model);
        return new FakeSyncHttp.Response(200, form);
    }

    /** What {@code openclaw agents list --json} prints now. */
    private void relist() {
        ArrayNode list = json.createArrayNode();
        openClawAgents.forEach((id, workspace) -> list.add(json.createObjectNode().put("id", id)
                .put("identityName", id.equals("coordinator") ? "P37 Chief of Staff" : id)
                .put("workspace", workspace.toString()).put("isDefault", id.equals("coordinator"))));
        cli.answerFirst(Tool.OPENCLAW, List.of("agents", "list"), 0, json.writeValueAsString(list));
    }

    private long agent(String name, String role, String specialization, String model) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"" + role + "\",\"specialization\":\"" + specialization
                                + "\",\"model\":" + model + "}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
    }

    @AfterEach
    void clean() throws IOException {
        jdbc.update("DELETE FROM agents WHERE name LIKE 'P37 %'");
        // Each test scripts an OpenClaw of its own: what was read from the previous one is let go.
        openClaw.forgetListing();
        cli.reset();
        http.reset();
        openClawAgents.clear();
        webUiModels.clear();
        delete0(locations.home().resolve(".openclaw"));
        if (litellmConfig != null) {
            delete0(Path.of(litellmConfig).getParent());
        }
    }

    private static void delete0(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // --- helpers -------------------------------------------------------------------------

    private JsonNode overview() throws Exception {
        return json.readTree(mockMvc.perform(get("/api/agent-sync")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The state of one agent in one tool, as the overview says it. */
    private String state(JsonNode overview, long agentId, String tool) {
        for (JsonNode row : overview.path("agents")) {
            if (row.path("agentId").asLong() == agentId) {
                for (JsonNode cell : row.path("cells")) {
                    if (cell.path("tool").asString().equals(tool)) {
                        return cell.path("state").asString();
                    }
                }
            }
        }
        return "no such cell";
    }

    private JsonNode publish(String body) throws Exception {
        return json.readTree(mockMvc.perform(asAdmin(post("/api/agent-sync/publish")).contentType(MediaType.APPLICATION_JSON)
                .content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, admin);
    }

    /** tool to outcome, for one agent of a publication report. */
    private Map<String, String> outcomes(JsonNode report, long agentId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (JsonNode result : report.path("results")) {
            if (result.path("agentId").asLong() == agentId) {
                out.put(result.path("tool").asString(), result.path("outcome").asString());
            }
        }
        return out;
    }

    private long openClawWrites() {
        return cli.calls().stream().filter(c -> c.tool() == Tool.OPENCLAW)
                .filter(c -> !c.arguments().subList(0, 2).equals(List.of("agents", "list"))).count();
    }

    // --- reading -------------------------------------------------------------------------

    @Test
    void theOverviewSaysWhereEveryAgentStandsAndWhatEachToolLacks() throws Exception {
        JsonNode overview = overview();

        assertThat(overview.path("tools")).extracting(t -> t.path("key").asString())
                .containsExactly("openclaw", "open-webui", "litellm");
        assertThat(overview.path("tools")).extracting(t -> t.path("availability").asString())
                .containsExactly("AVAILABLE", "AVAILABLE", "AVAILABLE");

        for (String tool : List.of("openclaw", "open-webui", "litellm")) {
            assertThat(state(overview, frontend, tool)).as(tool).isEqualTo("NOT_PUBLISHED");
        }
        // An agent without a local model: OpenClaw takes it (it keeps its own model), the other two cannot.
        assertThat(state(overview, architect, "openclaw")).isEqualTo("NOT_PUBLISHED");
        assertThat(state(overview, architect, "open-webui")).isEqualTo("NOT_APPLICABLE");
        assertThat(state(overview, architect, "litellm")).isEqualTo("NOT_APPLICABLE");
        assertThat(overview.path("pending").asInt()).isGreaterThanOrEqualTo(4);

        // OpenClaw's own coordinator can be brought in.
        assertThat(overview.path("importable")).anySatisfy(a -> {
            assertThat(a.path("tool").asString()).isEqualTo("openclaw");
            assertThat(a.path("externalId").asString()).isEqualTo("coordinator");
            assertThat(a.path("name").asString()).isEqualTo("P37 Chief of Staff");
        });
        // Reading wrote nothing anywhere.
        assertThat(openClawWrites()).isZero();
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
        assertThat(Files.readString(litellmFile)).isEqualTo(LITELLM);
    }

    /**
     * ADR-046: {@code openclaw agents list} takes 12-60 s on the operator's machine, and the page waited for it before
     * showing any agent. Here the command waits on a latch: the overview answers at once, with the other tools.
     */
    @Test
    void theOverviewDoesNotWaitForOpenClawAndFillsItInOnceItIsRead() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        cli.onRun(call -> {
            if (call.tool() == Tool.OPENCLAW && call.arguments().subList(0, 2).equals(List.of("agents", "list"))) {
                await(release);
            }
        });
        ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
        openClaw.background(background);
        try {
            long start = System.nanoTime();
            JsonNode first = overview();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

            JsonNode tool = first.path("tools").get(0);
            assertThat(tool.path("key").asString()).isEqualTo("openclaw");
            assertThat(tool.path("refreshing").asBoolean()).isTrue();
            assertThat(tool.path("checkedAt").isNull()).isTrue();
            assertThat(state(first, frontend, "openclaw")).isEqualTo("READING");
            // The other tools are not held up, and what is being read is not counted as work to do.
            assertThat(state(first, frontend, "open-webui")).isEqualTo("NOT_PUBLISHED");
            assertThat(state(first, frontend, "litellm")).isEqualTo("NOT_PUBLISHED");
            assertThat(first.path("pending").asInt()).isEqualTo(2);

            release.countDown();
            JsonNode after = first;
            for (int i = 0; i < 100 && after.path("tools").get(0).path("refreshing").asBoolean(); i++) {
                Thread.sleep(50);
                after = overview();
            }
            assertThat(after.path("tools").get(0).path("refreshing").asBoolean()).isFalse();
            assertThat(after.path("tools").get(0).path("checkedAt").isNull()).isFalse();
            assertThat(state(after, frontend, "openclaw")).isEqualTo("NOT_PUBLISHED");
            assertThat(after.path("importable")).anySatisfy(a -> assertThat(a.path("externalId").asString()).isEqualTo("coordinator"));
        } finally {
            release.countDown();
            openClaw.background(Runnable::run);
            background.shutdown();
        }
    }

    @Test
    void aReadingThatIsOldIsShownAtOnceAndRenewedBehindIt() throws Exception {
        overview();
        openClaw.ageListing();
        CountDownLatch release = new CountDownLatch(1);
        cli.onRun(call -> {
            if (call.tool() == Tool.OPENCLAW && call.arguments().subList(0, 2).equals(List.of("agents", "list"))) {
                await(release);
            }
        });
        ExecutorService background = Executors.newVirtualThreadPerTaskExecutor();
        openClaw.background(background);
        try {
            long start = System.nanoTime();
            JsonNode overview = overview();
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(3));

            assertThat(overview.path("tools").get(0).path("refreshing").asBoolean()).isTrue();
            assertThat(overview.path("tools").get(0).path("checkedAt").isNull()).isFalse();
            assertThat(state(overview, frontend, "openclaw")).isEqualTo("NOT_PUBLISHED");
        } finally {
            release.countDown();
            openClaw.background(Runnable::run);
            background.shutdown();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the command");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- writing -------------------------------------------------------------------------

    @Test
    void publishingBringsEveryToolUpToDateAndASecondTimeWritesNothing() throws Exception {
        JsonNode report = publish("{\"agentIds\":[" + frontend + "]}");

        assertThat(outcomes(report, frontend)).containsExactly(Map.entry("openclaw", "PUBLISHED"),
                Map.entry("open-webui", "PUBLISHED"), Map.entry("litellm", "PUBLISHED"));
        assertThat(report.path("written").asInt()).isEqualTo(3);
        assertThat(report.path("failed").asInt()).isZero();

        // Each tool holds the agent in its own shape.
        assertThat(openClawAgents).containsKey("aicos-p37-frontend");
        assertThat(Files.readString(openClawAgents.get("aicos-p37-frontend").resolve("AGENTS.md")))
                .startsWith(OpenClawConnector.MARKER).contains("P37 Frontend");
        assertThat(webUiModels.get("aicos-p37-frontend").path("base_model_id").asString()).isEqualTo("qwen3.5:9b");
        assertThat(Files.readString(litellmFile)).contains("- model_name: aicos-p37-frontend").contains("ollama_chat/qwen3.5:9b");

        JsonNode after = overview();
        for (String tool : List.of("openclaw", "open-webui", "litellm")) {
            assertThat(state(after, frontend, tool)).as(tool).isEqualTo("IN_SYNC");
        }

        long writes = openClawWrites();
        long posts = http.calls().stream().filter(c -> c.method().equals("POST")).count();
        String file = Files.readString(litellmFile);
        JsonNode again = publish("{\"agentIds\":[" + frontend + "]}");

        assertThat(outcomes(again, frontend).values()).containsOnly("UNCHANGED");
        assertThat(openClawWrites()).isEqualTo(writes);
        assertThat(http.calls().stream().filter(c -> c.method().equals("POST")).count()).isEqualTo(posts);
        assertThat(Files.readString(litellmFile)).isEqualTo(file);
    }

    @Test
    void anAgentChangedHereIsBehindOnlyWhereTheChangeIsSomethingTheToolWasGiven() throws Exception {
        publish("{\"agentIds\":[" + frontend + "]}");

        // The role instructions: OpenClaw's program and Open WebUI's system prompt carry them, a LiteLLM alias does not.
        jdbc.update("UPDATE agents SET system_prompt = 'Scrivi componenti accessibili.' WHERE id = ?", frontend);

        JsonNode overview = overview();
        assertThat(state(overview, frontend, "openclaw")).isEqualTo("BEHIND");
        assertThat(state(overview, frontend, "open-webui")).isEqualTo("BEHIND");
        assertThat(state(overview, frontend, "litellm")).isEqualTo("IN_SYNC");

        JsonNode report = publish("{}");

        assertThat(outcomes(report, frontend)).containsExactly(Map.entry("openclaw", "UPDATED"),
                Map.entry("open-webui", "UPDATED"), Map.entry("litellm", "UNCHANGED"));
        assertThat(webUiModels.get("aicos-p37-frontend").path("params").path("system").asString())
                .contains("Scrivi componenti accessibili.");
        assertThat(Files.readString(openClawAgents.get("aicos-p37-frontend").resolve("AGENTS.md")))
                .contains("Scrivi componenti accessibili.");
        assertThat(state(overview(), frontend, "openclaw")).isEqualTo("IN_SYNC");
        // "Everything" published the architect too, where it can stand.
        assertThat(outcomes(report, architect)).containsExactly(Map.entry("openclaw", "PUBLISHED"),
                Map.entry("open-webui", "SKIPPED"), Map.entry("litellm", "SKIPPED"));
    }

    @Test
    void anAgentTheToolNoLongerHasIsMissingAndIsPublishedAgain() throws Exception {
        publish("{\"agentIds\":[" + frontend + "],\"tools\":[\"open-webui\"]}");
        webUiModels.clear();

        assertThat(state(overview(), frontend, "open-webui")).isEqualTo("MISSING");

        JsonNode report = publish("{\"agentIds\":[" + frontend + "],\"tools\":[\"open-webui\"]}");

        assertThat(outcomes(report, frontend)).containsExactly(Map.entry("open-webui", "PUBLISHED"));
        assertThat(webUiModels).containsKey("aicos-p37-frontend");
    }

    @Test
    void whatAToolRefusesIsAFailedResultAndLeavesTheAgentNotPublished() throws Exception {
        http.answer("POST", "/api/v1/models/create", 400, "{\"detail\":\"Model id is too long\"}");

        JsonNode report = publish("{\"agentIds\":[" + frontend + "],\"tools\":[\"open-webui\"]}");

        assertThat(outcomes(report, frontend)).containsExactly(Map.entry("open-webui", "FAILED"));
        assertThat(report.path("failed").asInt()).isEqualTo(1);
        assertThat(report.path("results").get(0).path("detail").asString()).contains("Model id is too long");
        assertThat(state(overview(), frontend, "open-webui")).isEqualTo("NOT_PUBLISHED");
    }

    @Test
    void twoAgentsWhoseNamesGiveOneIdentifierDoNotShareAnAgentInTheTool() throws Exception {
        long twin = agent("P37 frontend!", "Sviluppatore frontend", "Vue", "\"ollama:qwen3.5:4b\"");

        publish("{\"agentIds\":[" + frontend + "," + twin + "],\"tools\":[\"litellm\"]}");

        assertThat(LiteLlmConnector.modelNames(Files.readString(litellmFile)))
                .contains("aicos-p37-frontend", "aicos-p37-frontend-" + twin);
    }

    @Test
    void takingAnAgentOutOfAToolRemovesWhatWasMadeThere() throws Exception {
        publish("{\"agentIds\":[" + frontend + "]}");

        for (String tool : List.of("litellm", "open-webui", "openclaw")) {
            mockMvc.perform(asAdmin(delete("/api/agent-sync/" + tool + "/agents/" + frontend))).andExpect(status().isNoContent());
        }

        assertThat(Files.readString(litellmFile)).isEqualTo(LITELLM);
        assertThat(webUiModels).isEmpty();
        assertThat(openClawAgents).containsOnlyKeys("coordinator");
        assertThat(state(overview(), frontend, "openclaw")).isEqualTo("NOT_PUBLISHED");
        // Asked again, there is nothing left to take out.
        mockMvc.perform(asAdmin(delete("/api/agent-sync/litellm/agents/" + frontend))).andExpect(status().isNoContent());
    }

    // --- OpenClaw's own agents -----------------------------------------------------------

    @Test
    void anAgentOpenClawAlreadyHadIsBroughtInLinkedAndNeverRewritten() throws Exception {
        JsonNode imported = json.readTree(mockMvc.perform(asAdmin(post("/api/agent-sync/import"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"openclaw\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(imported).hasSize(1);
        long chief = imported.get(0).path("agentId").asLong();
        assertThat(imported.get(0).path("externalId").asString()).isEqualTo("coordinator");
        // Here it is an agent that works through OpenClaw, told what OpenClaw tells it.
        JsonNode agent = json.readTree(mockMvc.perform(get("/api/agents/" + chief + "/configuration"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(agent.toString()).contains("P37 Chief of Staff").contains("openclaw");
        assertThat(jdbc.queryForObject("SELECT system_prompt FROM agents WHERE id = ?", String.class, chief)).contains("Own the outcome.");
        assertThat(jdbc.queryForObject("SELECT specialization FROM agents WHERE id = ?", String.class, chief))
                .isEqualTo("Chief of staff operating program");

        JsonNode overview = overview();
        assertThat(state(overview, chief, "openclaw")).isEqualTo("ADOPTED");
        assertThat(overview.path("importable")).noneMatch(a -> a.path("externalId").asString().equals("coordinator"));

        // Synchronising everything leaves OpenClaw's own agent and its files alone...
        String program = Files.readString(openClawAgents.get("coordinator").resolve("AGENTS.md"));
        JsonNode report = publish("{\"agentIds\":[" + chief + "]}");
        assertThat(outcomes(report, chief).get("openclaw")).isEqualTo("SKIPPED");
        assertThat(openClawWrites()).isZero();
        assertThat(Files.readString(openClawAgents.get("coordinator").resolve("AGENTS.md"))).isEqualTo(program);
        // ...a second import brings nothing twice...
        mockMvc.perform(asAdmin(post("/api/agent-sync/import")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"openclaw\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        // ...and unlinking it removes nothing from OpenClaw.
        mockMvc.perform(asAdmin(delete("/api/agent-sync/openclaw/agents/" + chief))).andExpect(status().isNoContent());
        assertThat(openClawAgents).containsKey("coordinator");
        assertThat(cli.calls()).noneMatch(c -> c.arguments().contains("delete"));
    }

    @Test
    void onlyAToolWithAgentsOfItsOwnIsImportedFromAndOnlyWhenItAnswers() throws Exception {
        mockMvc.perform(asAdmin(post("/api/agent-sync/import")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"litellm\"}")).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:sync-refused"));

        Files.delete(openClawState.resolve("openclaw.json"));
        mockMvc.perform(asAdmin(post("/api/agent-sync/import")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"openclaw\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:sync-tool-unavailable"));

        mockMvc.perform(asAdmin(post("/api/agent-sync/import")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tool\":\"nowhere\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:sync-tool-not-found"));
        // LM Studio left the ecosystem on 2026-11-12: it is not a tool agents are written to.
        mockMvc.perform(asAdmin(post("/api/agent-sync/publish")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tools\":[\"lm-studio\"]}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:sync-tool-not-found"));
    }

    // --- who may write -------------------------------------------------------------------

    @Test
    void writingAToolsConfigurationIsAnAdministratorsDecision() throws Exception {
        mockMvc.perform(get("/api/agent-sync")).andExpect(status().isOk());

        mockMvc.perform(post("/api/agent-sync/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/agent-sync/import").contentType(MediaType.APPLICATION_JSON).content("{\"tool\":\"openclaw\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/agent-sync/litellm/agents/" + frontend)).andExpect(status().isForbidden());

        assertThat(openClawWrites()).isZero();
        assertThat(http.calls()).noneMatch(c -> c.method().equals("POST"));
        assertThat(Files.readString(litellmFile)).isEqualTo(LITELLM);
    }
}
