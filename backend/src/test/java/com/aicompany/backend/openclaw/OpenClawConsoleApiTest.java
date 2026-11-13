package com.aicompany.backend.openclaw;

import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.harness.hub.SkillLocations;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import com.aicompany.backend.support.FakeHost;
import com.aicompany.backend.support.FakeHubCommands;
import com.aicompany.backend.support.FakeSyncHttp;
import com.aicompany.backend.sync.AgentPublications;
import com.aicompany.backend.sync.OpenClawConnector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-045: OpenClaw inside the console. Its Control UI cannot be framed, so the console
 * shows what the operator works with -- agents, sessions, a conversation, a session taken
 * up in a terminal -- through {@code openclaw}'s own commands, and opens the full Control
 * UI with {@code openclaw dashboard}, which pairs the browser itself.
 *
 * <p>OpenClaw here is a scripted command and a scripted gateway health route; the
 * console's readings run right away ({@code aicos.openclaw.background=false}).
 */
class OpenClawConsoleApiTest extends AbstractPostgresTest {

    private static final String AGENTS = """
            [{"id":"coordinator","identityName":"Chief of Staff","workspace":"C:\\\\oc\\\\coordinator","isDefault":true},
             {"id":"aicos-p45-writer","identityName":"P45 Writer","workspace":"C:\\\\oc\\\\writer","isDefault":false}]
            """;
    private static final String SESSIONS = """
            [state/agent-db] opening 2 stores
            {"count":3,"sessions":[
              {"key":"agent:coordinator:main","agentId":"coordinator","updatedAt":1794323344817,"model":"gpt-6-astra",
               "modelProvider":"openai","totalTokens":1200,"kind":"direct"},
              {"key":"agent:aicos-p45-writer:aicos-task-42","agentId":"aicos-p45-writer","updatedAt":1794435830308,
               "label":"Task 42","totalTokens":null},
              {"key":"agent:coordinator:aicos-console","agentId":"coordinator","updatedAt":1794435830000},
              {"key":"agent:x:bad;key","agentId":"x"}
            ]}
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
    private FakeHost host;

    @Autowired
    private OpenClawConnector connector;

    @Autowired
    private OpenClawConsole console;

    @Autowired
    private AgentPublications publications;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;
    private long writer;

    @BeforeEach
    void anOpenClawWithTwoAgentsAndAGatewayThatAnswers() throws Exception {
        clean();
        admin = AdminSession.bearer(mockMvc);
        Path state = Files.createDirectories(locations.home().resolve(".openclaw"));
        Files.writeString(state.resolve("openclaw.json"), "{}");
        http.answer("GET", "/healthz", 200, "{\"ok\":true}");
        cli.answer(Tool.OPENCLAW, List.of("agents", "list"), 0, AGENTS);
        cli.answer(Tool.OPENCLAW, List.of("sessions"), 0, SESSIONS);
        writer = json.readTree(mockMvc.perform(asAdmin(post("/api/agents")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"P45 Writer\",\"role\":\"Writer\",\"specialization\":\"Docs\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).path("id").asLong();
        publications.record(writer, OpenClawConnector.KEY, "aicos-p45-writer", "f", true, "test");
    }

    @AfterEach
    void clean() throws IOException {
        jdbc.update("DELETE FROM agents WHERE name LIKE 'P45 %'");
        connector.forgetListing();
        console.forget();
        cli.reset();
        http.reset();
        host.reset();
        Path state = locations.home().resolve(".openclaw");
        if (Files.exists(state)) {
            try (Stream<Path> walk = Files.walk(state)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(p);
                }
            }
        }
    }

    // --- reading -------------------------------------------------------------------------

    @Test
    void theOverviewShowsOpenClawsAgentsAndSessionsTiedToWhatTheyAreHere() throws Exception {
        JsonNode overview = overview("");

        assertThat(overview.path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(overview.path("gatewayUrl").asString()).isEqualTo("http://openclaw.test:18789");
        assertThat(overview.path("refreshedAt").isNull()).isFalse();
        assertThat(overview.path("refreshing").asBoolean()).isFalse();

        JsonNode agents = overview.path("agents");
        assertThat(agents).extracting(a -> a.path("id").asString()).containsExactly("coordinator", "aicos-p45-writer");
        assertThat(agents.get(0).path("isDefault").asBoolean()).isTrue();
        assertThat(agents.get(0).path("aicosAgentId").isNull()).isTrue();
        assertThat(agents.get(1).path("madeHere").asBoolean()).isTrue();
        assertThat(agents.get(1).path("aicosAgentId").asLong()).isEqualTo(writer);
        assertThat(agents.get(1).path("aicosAgentName").asString()).isEqualTo("P45 Writer");

        // A session key Windows Terminal would read as syntax is not shown, so it can never be opened.
        JsonNode sessions = overview.path("sessions");
        assertThat(sessions).extracting(s -> s.path("key").asString()).containsExactly("agent:coordinator:main",
                "agent:aicos-p45-writer:aicos-task-42", "agent:coordinator:aicos-console");
        assertThat(sessions.get(0).path("model").asString()).isEqualTo("gpt-6-astra");
        assertThat(sessions.get(0).path("provider").asString()).isEqualTo("openai");
        assertThat(sessions.get(0).path("totalTokens").asLong()).isEqualTo(1200);
        assertThat(sessions.get(1).path("taskId").asLong()).isEqualTo(42);
        assertThat(sessions.get(1).path("totalTokens").isNull()).isTrue();
        assertThat(sessions.get(2).path("console").asBoolean()).isTrue();
        assertThat(sessions.get(0).path("console").asBoolean()).isFalse();

        // Reading asked OpenClaw and changed nothing in it.
        assertThat(cli.calls()).extracting(c -> c.arguments().getFirst()).containsOnly("agents", "sessions");
    }

    @Test
    void aReadingIsKeptAndALookWithinHalfAMinuteDoesNotRunOpenClawAgain() throws Exception {
        overview("");
        int asked = cli.calls().size();

        overview("");
        assertThat(cli.calls()).hasSize(asked);

        connector.forgetListing();
        overview("?refresh=true");
        assertThat(cli.calls().size()).isGreaterThan(asked);
    }

    @Test
    void aGatewayThatIsDownIsSaidAtOnceWithoutRunningOpenClaw() throws Exception {
        http.reset();

        JsonNode overview = overview("");

        assertThat(overview.path("availability").asString()).isEqualTo("UNREACHABLE");
        assertThat(overview.path("hint").asString()).contains("openclaw daemon start");
        assertThat(overview.path("agents")).isEmpty();
        assertThat(cli.calls()).isEmpty();
        mockMvc.perform(asAdmin(post("/api/openclaw/messages")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"coordinator\",\"message\":\"ciao\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:sync-tool-unavailable"));
    }

    @Test
    void aReadingThatFailsKeepsTheOneBeforeAndSaysWhy() throws Exception {
        overview("");
        cli.answerFirst(Tool.OPENCLAW, List.of("sessions"), 1, "SQLite read-only worker failed");

        JsonNode overview = overview("?refresh=true");

        assertThat(overview.path("problem").asString()).contains("SQLite read-only worker failed");
        assertThat(overview.path("sessions")).hasSize(3);
    }

    // --- talking to an agent -------------------------------------------------------------

    @Test
    void aMessageGoesAsAFileToTheConsolesSessionWithTheAgentAndTheAnswerIsRead() throws Exception {
        overview("");
        AtomicReference<String> delivered = new AtomicReference<>();
        AtomicReference<Path> file = new AtomicReference<>();
        cli.onRun(call -> {
            List<String> a = call.arguments();
            if (a.getFirst().equals("agent")) {
                Path path = Path.of(a.get(a.indexOf("--message-file") + 1));
                file.set(path);
                try {
                    delivered.set(Files.readString(path, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
        cli.answer(Tool.OPENCLAW, List.of("agent"), 0, """
                {"runId":"r1","status":"ok","result":{"payloads":[{"text":"Fatto."},{"text":"Ho letto il file."}],
                 "meta":{"agentMeta":{"model":"gpt-6-astra","provider":"openai","usage":{"total":321}}}}}
                warning: a line after the JSON
                """);
        String message = "Leggi \"README.md\"; poi\ndimmi che cosa manca & perché.";

        JsonNode sent = json.readTree(mockMvc.perform(asAdmin(post("/api/openclaw/messages")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("agentId", "coordinator").put("message", message))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());

        List<String> arguments = cli.calls().stream().filter(c -> c.arguments().getFirst().equals("agent")).findFirst()
                .orElseThrow().arguments();
        assertThat(arguments).containsSubsequence("agent", "--agent", "coordinator", "--session-key",
                "agent:coordinator:aicos-console", "--message-file").contains("--json", "--timeout", "600");
        assertThat(arguments).noneMatch(a -> a.contains("README"));
        assertThat(delivered.get()).isEqualTo(message);
        assertThat(file.get()).doesNotExist();

        JsonNode answer = json.readTree(mockMvc.perform(get("/api/openclaw/messages/" + sent.path("id").asString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(answer.path("state").asString()).isEqualTo("DONE");
        assertThat(answer.path("reply").asString()).isEqualTo("Fatto.\n\nHo letto il file.");
        assertThat(answer.path("model").asString()).isEqualTo("gpt-6-astra");
        assertThat(answer.path("tokens").asLong()).isEqualTo(321);
        assertThat(answer.path("sentBy").asString()).isEqualTo("admin");

        JsonNode kept = json.readTree(mockMvc.perform(get("/api/openclaw/messages")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(kept).extracting(m -> m.path("id").asString()).containsExactly(sent.path("id").asString());
    }

    @Test
    void aTurnOpenClawDidNotFinishIsAFailedMessageWithItsReason() throws Exception {
        overview("");
        cli.answer(Tool.OPENCLAW, List.of("agent"), 1, """
                {"status":"error","error":{"message":"Provider quota exceeded","kind":"provider"},"result":{"payloads":[]}}
                """);

        JsonNode answer = send("coordinator", "ciao");

        assertThat(answer.path("state").asString()).isEqualTo("FAILED");
        assertThat(answer.path("detail").asString()).contains("Provider quota exceeded");
        assertThat(answer.path("reply").isNull()).isTrue();
    }

    @Test
    void anAgentAnswersOneMessageAtATime() throws Exception {
        overview("");
        List<Integer> second = new ArrayList<>();
        cli.onRun(call -> {
            if (call.arguments().getFirst().equals("agent") && second.isEmpty()) {
                try {
                    second.add(mockMvc.perform(asAdmin(post("/api/openclaw/messages")).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"agentId\":\"coordinator\",\"message\":\"e poi?\"}")).andReturn().getResponse().getStatus());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
        });
        cli.answer(Tool.OPENCLAW, List.of("agent"), 0, "{\"status\":\"ok\",\"result\":{\"payloads\":[{\"text\":\"ok\"}]}}");

        assertThat(send("coordinator", "prima").path("state").asString()).isEqualTo("DONE");

        assertThat(second).containsExactly(502);
        assertThat(cli.calls()).filteredOn(c -> c.arguments().getFirst().equals("agent")).hasSize(1);
    }

    @Test
    void onlyAnAgentOpenClawHasIsWrittenTo() throws Exception {
        overview("");
        mockMvc.perform(asAdmin(post("/api/openclaw/messages")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"nobody\",\"message\":\"ciao\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:openclaw-item-not-found"));
        mockMvc.perform(asAdmin(post("/api/openclaw/messages")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentId\":\"--model\",\"message\":\"ciao\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/openclaw/messages/never-sent")).andExpect(status().isNotFound());
        assertThat(cli.calls()).noneMatch(c -> c.arguments().getFirst().equals("agent"));
    }

    // --- the Control UI and the terminal -------------------------------------------------

    @Test
    void theControlUiIsOpenedByOpenClawItself() throws Exception {
        cli.answer(Tool.OPENCLAW, List.of("dashboard"), 0, """
                Dashboard URL: http://127.0.0.1:18789/
                One-time browser pairing included in browser/clipboard URL.
                Opened in your browser. Keep that tab to control OpenClaw.
                """);

        mockMvc.perform(asAdmin(post("/api/openclaw/control-ui"))).andExpect(status().isAccepted());

        assertThat(cli.calls()).extracting(FakeHubCommands.Call::arguments).contains(List.of("dashboard"));
        assertThat(overview("").path("controlUi").path("state").asString()).isEqualTo("OPENED");
    }

    @Test
    void aControlUiThatDidNotOpenSaysSoWithoutRepeatingThePairingLink() throws Exception {
        cli.answer(Tool.OPENCLAW, List.of("dashboard"), 0,
                "Copy to clipboard unavailable.\nOpen http://127.0.0.1:18789/#bootstrap=one-time-secret to pair.\n");

        mockMvc.perform(asAdmin(post("/api/openclaw/control-ui"))).andExpect(status().isAccepted());

        JsonNode controlUi = overview("").path("controlUi");
        assertThat(controlUi.path("state").asString()).isEqualTo("FAILED");
        assertThat(controlUi.path("detail").asString()).contains("http://127.0.0.1:18789/").doesNotContain("one-time-secret");
    }

    @Test
    void aSessionIsTakenUpInOpenClawsTerminalInterface() throws Exception {
        JsonNode opened = json.readTree(mockMvc.perform(asAdmin(post("/api/openclaw/terminal")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"agent:aicos-p45-writer:aicos-task-42\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(opened.path("command").asString()).contains("openclaw tui --session agent:aicos-p45-writer:aicos-task-42");
        assertThat(host.launches()).extracting(LaunchPlan::command).anySatisfy(command ->
                assertThat(command).containsSubsequence("openclaw", "tui", "--session", "agent:aicos-p45-writer:aicos-task-42"));

        mockMvc.perform(asAdmin(post("/api/openclaw/terminal")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionKey\":\"agent:x:main; calc\"}"))
                .andExpect(status().isBadRequest());
        assertThat(host.launches()).hasSize(1);
    }

    // --- who may act ---------------------------------------------------------------------

    @Test
    void everythingThatActsIsAnAdministratorsDecision() throws Exception {
        mockMvc.perform(get("/api/openclaw")).andExpect(status().isOk());

        mockMvc.perform(post("/api/openclaw/messages").contentType(MediaType.APPLICATION_JSON)
                .content("{\"agentId\":\"coordinator\",\"message\":\"ciao\"}")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/openclaw/control-ui")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/openclaw/terminal").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sessionKey\":\"agent:coordinator:main\"}")).andExpect(status().isForbidden());

        assertThat(cli.calls()).noneMatch(c -> List.of("agent", "dashboard").contains(c.arguments().getFirst()));
        assertThat(host.launches()).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------------

    private JsonNode overview(String query) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/openclaw" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(String agentId, String message) throws Exception {
        JsonNode sent = json.readTree(mockMvc.perform(asAdmin(post("/api/openclaw/messages")).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(json.createObjectNode().put("agentId", agentId).put("message", message))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        return json.readTree(mockMvc.perform(get("/api/openclaw/messages/" + sent.path("id").asString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, admin);
    }
}
