package com.aicompany.backend.usage;

import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 35 (ADR-041, phase 1): what the agents consume is measured before anything is
 * optimised. A run keeps what the provider reported -- tokens, its durations, the window
 * it was asked for -- and a handoff keeps the size of its package and of every file the
 * package asks the agent to read. The usage page lists them per run, per agent, per
 * model; a value nobody measured stays unknown.
 */
class AgentMeasuresApiTest extends AbstractPostgresTest {

    private static final String SKILL = "p35-measured-rules";
    private static final String MODEL = "ollama:p35-measured:9b";

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

    @BeforeEach
    void anAgentWithASkill() throws Exception {
        clean();
        engine.reset();
        String skill = """
                ---
                key: %s
                name: Regole misurate
                kind: SKILL
                description: Regole per la misura.
                ---

                # Regole misurate

                - Ogni numero mostrato è un numero misurato: «già», più di un byte per carattere.
                """.formatted(SKILL);
        mockMvc.perform(post("/api/library/entries").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(json.createObjectNode().put("content", skill))))
                .andExpect(status().isCreated());
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P35 Measured Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"SQL\"}")).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/agents/" + agentId + "/resources/" + SKILL)).andExpect(status().isNoContent());
    }

    @AfterEach
    void clean() throws Exception {
        engine.release();
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM task_runs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P35 %'");
        jdbc.update("DELETE FROM projects WHERE name = 'Misure P35'");
        jdbc.update("DELETE FROM agent_resources WHERE agent_id IN (SELECT id FROM agents WHERE name = 'P35 Measured Bot')");
        jdbc.update("DELETE FROM agents WHERE name = 'P35 Measured Bot'");
        jdbc.update("DELETE FROM agent_resources WHERE resource_id IN (SELECT id FROM harness_resources WHERE key = ?)", SKILL);
        jdbc.update("DELETE FROM harness_resources WHERE key = ?", SKILL);
        Path entry = library.root().resolve("skills").resolve(SKILL);
        if (Files.exists(entry)) {
            try (Stream<Path> walk = Files.walk(entry)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ------------------------------------------------------------------
    // Runs
    // ------------------------------------------------------------------

    @Test
    void aRunKeepsWhatTheProviderMeasuredAndTheUsagePageGroupsItPerModelAndPerAgent() throws Exception {
        long taskId = task("P35 Misura le run", null);

        engine.answerWith(new EngineClient.Completion("Prima risposta", "stop", MODEL, 1200, 80, 9950,
                new EngineClient.Metrics(9877L, 2500L, 4000L, 3300L, 16384)));
        long first = runAndWait(taskId);
        engine.answerWith(new EngineClient.Completion("Seconda risposta", "stop", MODEL, 600, 40, 2900,
                new EngineClient.Metrics(2877L, 0L, 1000L, 1700L, 16384)));
        long second = runAndWait(taskId);
        // The default scripted answer is the echo: it measures nothing.
        long third = runAndWait(taskId);

        JsonNode run = json.readTree(mockMvc.perform(get("/api/runs/" + first)).andReturn().getResponse().getContentAsString());
        assertThat(run.path("providerTotalMs").asLong()).isEqualTo(9877);
        assertThat(run.path("loadMs").asLong()).isEqualTo(2500);
        assertThat(run.path("promptEvalMs").asLong()).isEqualTo(4000);
        assertThat(run.path("evalMs").asLong()).isEqualTo(3300);
        assertThat(run.path("contextWindow").asInt()).isEqualTo(16384);
        JsonNode echo = json.readTree(mockMvc.perform(get("/api/runs/" + third)).andReturn().getResponse().getContentAsString());
        assertThat(echo.path("providerTotalMs").isNull()).as("not measured is unknown, not zero").isTrue();
        assertThat(echo.path("contextWindow").isNull()).isTrue();

        JsonNode measures = measurements();

        JsonNode listed = find(measures.path("runs"), "runId", first);
        assertThat(listed.path("taskTitle").asString()).isEqualTo("P35 Misura le run");
        assertThat(listed.path("agentName").asString()).isEqualTo("P35 Measured Bot");
        assertThat(listed.path("model").asString()).isEqualTo(MODEL);
        assertThat(listed.path("inputTokens").asInt()).isEqualTo(1200);
        assertThat(listed.path("outputTokens").asInt()).isEqualTo(80);
        assertThat(listed.path("providerTotalMs").asLong()).isEqualTo(9877);
        assertThat(listed.path("loadMs").asLong()).isEqualTo(2500);
        assertThat(listed.path("contextWindow").asInt()).isEqualTo(16384);
        // The prompts' size is counted on what was sent -- characters, never turned into tokens.
        String system = jdbc.queryForObject("SELECT system_prompt FROM task_runs WHERE id = ?", String.class, first);
        String user = jdbc.queryForObject("SELECT user_prompt FROM task_runs WHERE id = ?", String.class, first);
        assertThat(listed.path("systemChars").asInt()).isEqualTo(system.codePointCount(0, system.length()));
        assertThat(listed.path("userChars").asInt()).isEqualTo(user.codePointCount(0, user.length()));
        assertThat(ids(measures.path("runs"))).as("newest first").containsSubsequence(third, second, first);

        JsonNode model = find(measures.path("byModel"), "key", MODEL);
        assertThat(model.path("runs").asInt()).isEqualTo(2);
        assertThat(model.path("timedRuns").asInt()).isEqualTo(2);
        assertThat(model.path("inputTokens").asLong()).isEqualTo(1800);
        assertThat(model.path("outputTokens").asLong()).isEqualTo(120);
        assertThat(model.path("avgInputTokens").asLong()).isEqualTo(900);
        assertThat(model.path("avgProviderTotalMs").asLong()).isEqualTo(6377);
        assertThat(model.path("avgLoadMs").asLong()).isEqualTo(1250);
        assertThat(model.path("outputTokensPerSecond").asDouble()).isEqualTo(24.0);
        assertThat(model.path("contextWindows").toString()).isEqualTo("[16384]");

        JsonNode unmeasured = find(measures.path("byModel"), "key", "echo:default");
        assertThat(unmeasured.path("timedRuns").asInt()).isZero();
        assertThat(unmeasured.path("avgProviderTotalMs").isNull()).as("an average of nothing is not a number").isTrue();
        assertThat(unmeasured.path("outputTokensPerSecond").isNull()).isTrue();

        JsonNode agent = find(measures.path("byAgent"), "key", "P35 Measured Bot");
        assertThat(agent.path("agentId").asLong()).isEqualTo(agentId);
        assertThat(agent.path("runs").asInt()).isGreaterThanOrEqualTo(3);
        assertThat(agent.path("timedRuns").asInt()).isEqualTo(2);
    }

    // ------------------------------------------------------------------
    // Handoffs
    // ------------------------------------------------------------------

    @Test
    void aHandoffKeepsTheSizeOfItsPackageAndOfEachFileItAsksToRead() throws Exception {
        long projectId = project();
        long taskId = task("P35 Misura il pacchetto", projectId);

        // A target that does not load skills by itself: its package still asks to read every file (ADR-041 §8).
        MvcResult result = mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"manual\"}"))
                .andExpect(status().isAccepted()).andReturn();
        long handoffId = json.readTree(result.getResponse().getContentAsString()).path("handoff").path("id").asLong();

        Path workspace = folder.resolve("misure");
        String label = "TASK-" + taskId;
        String skillFile = library.root().resolve("skills/" + SKILL + "/SKILL.md").toString();

        JsonNode handoff = find(measurements().path("handoffs"), "handoffId", handoffId);
        assertThat(handoff.path("target").asString()).isEqualTo("manual");
        assertThat(handoff.path("taskTitle").asString()).isEqualTo("P35 Misura il pacchetto");
        assertThat(handoff.path("packageChars").asInt())
                .isEqualTo(chars(workspace.resolve(".aicos/handoffs/" + label + "-manual.md")));

        List<String> seen = new ArrayList<>();
        for (JsonNode read : handoff.path("reads")) {
            seen.add(read.path("kind").asString() + " " + read.path("path").asString() + " " + read.path("required").asBoolean());
        }
        assertThat(seen).as("the context section in its order, then the library files").containsExactly(
                "GOVERNANCE AGENTS.md true",
                "TASK tasks/" + label + ".md true",
                "CONTEXT_MAP .aicos/CONTEXT_MAP.md false",
                "SKILL " + skillFile + " true");
        JsonNode reads = handoff.path("reads");
        assertThat(reads.get(0).path("chars").asInt()).isEqualTo(chars(workspace.resolve("AGENTS.md")));
        assertThat(reads.get(1).path("chars").asInt()).isEqualTo(chars(workspace.resolve("tasks/" + label + ".md")));
        assertThat(reads.get(2).path("chars").asInt()).isEqualTo(chars(workspace.resolve(".aicos/CONTEXT_MAP.md")));
        // The whole file, frontmatter included: that is what the agent opens.
        assertThat(reads.get(3).path("chars").asInt()).isEqualTo(chars(Path.of(skillFile)));
        assertThat(handoff.path("requiredChars").asInt()).isEqualTo(reads.get(0).path("chars").asInt()
                + reads.get(1).path("chars").asInt() + reads.get(3).path("chars").asInt());
        assertThat(handoff.path("optionalChars").asInt()).isEqualTo(reads.get(2).path("chars").asInt());
        assertThat(handoff.path("missingFiles").asInt()).isZero();
    }

    @Test
    void aHandoffRecordedBeforeTheMeasuresSaysItWasNotMeasured() throws Exception {
        long taskId = task("P35 Handoff di prima", null);
        Long handoffId = jdbc.queryForObject("INSERT INTO task_handoffs (task_id, target, document_path, prompt, requested_by, "
                + "created_at) VALUES (?, 'codex', 'TASK.md', 'Leggi', 'operator', now()) RETURNING id", Long.class, taskId);

        JsonNode handoff = find(measurements().path("handoffs"), "handoffId", handoffId);
        assertThat(handoff.path("packageChars").isNull()).isTrue();
        assertThat(handoff.path("requiredChars").isNull()).as("nothing was measured, so nothing is summed").isTrue();
        assertThat(handoff.path("reads").size()).isZero();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private JsonNode measurements() throws Exception {
        String today = LocalDate.now().toString();
        return json.readTree(mockMvc.perform(get("/api/usage/measurements").param("from", today).param("to", today))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static JsonNode find(JsonNode array, String field, Object value) {
        for (JsonNode item : array) {
            if (item.path(field).asString().equals(String.valueOf(value))) {
                return item;
            }
        }
        throw new AssertionError("no element with " + field + " = " + value + " in " + array);
    }

    private static List<Long> ids(JsonNode runs) {
        List<Long> ids = new ArrayList<>();
        runs.forEach(r -> ids.add(r.path("runId").asLong()));
        return ids;
    }

    private static int chars(Path file) throws Exception {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        return text.codePointCount(0, text.length());
    }

    private long project() throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Misure P35\"}")).andReturn();
        long id = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"DELEGATED\",\"workspacePath\":\""
                        + folder.resolve("misure").toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        return id;
    }

    private long task(String title, Long projectId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"HIGH\""
                        + (projectId == null ? "" : ",\"projectId\":" + projectId) + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private long runAndWait(long taskId) throws Exception {
        MvcResult launched = mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted()).andReturn();
        long runId = json.readTree(launched.getResponse().getContentAsString()).path("id").asLong();
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = json.readTree(mockMvc.perform(get("/api/runs/" + runId)).andReturn().getResponse().getContentAsString());
            String status = run.path("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                return runId;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("run " + runId + " did not finish within 15 s");
    }
}
