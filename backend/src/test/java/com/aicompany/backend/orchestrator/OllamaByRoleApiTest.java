package com.aicompany.backend.orchestrator;

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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 35 (ADR-041, phase 4): how each local model runs is in the catalog -- window,
 * keep-alive, recommended quantization -- and reaches Ollama through the engine; which
 * model a task without one gets is a catalog rule too (short work to the small model,
 * planning and code to the 9B); and the local models run one at a time.
 */
class OllamaByRoleApiTest extends AbstractPostgresTest {

    private static final String QWEN9 = "ollama:qwen3.5:9b";
    private static final String QWEN4 = "ollama:qwen3.5:4b";
    private static final String LFM = "ollama:lfm2.5:8b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void theEngineServesTheLocalModels() {
        clean();
        engine.reset();
        List.of(QWEN9, QWEN4, LFM).forEach(engine::serves);
    }

    @AfterEach
    void clean() {
        engine.release();
        jdbc.update("DELETE FROM task_runs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P35 %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P35 %'");
        jdbc.update("DELETE FROM agents WHERE name LIKE 'P35 %'");
    }

    // ------------------------------------------------------------------ the settings

    @Test
    void theCatalogSaysHowEachLocalModelRuns() throws Exception {
        assertSettings(model(QWEN9), 16384, "5m", "Q4_K_M");
        assertSettings(model(QWEN4), 8192, "2m", "Q4_K_M");
        assertSettings(model(LFM), 8192, "2m", "Q4_K_M");
        assertSettings(model("ollama:gemma4:e4b"), 8192, "5m", "Q4_K_M");
        assertSettings(model("ollama:deepseek-coder-v2:16b"), 4096, "1m", "Q4_0");
        // A model that is not run by Ollama has none.
        assertThat(model("claude-subscription:default").path("numCtx").isNull()).isTrue();
    }

    @Test
    void theOperatorTunesAModelAndWhatIsNotAWindowOrADurationIsRefused() throws Exception {
        JsonNode qwen4 = model(QWEN4);
        try {
            tune(qwen4, "\"numCtx\":6144,\"keepAlive\":\"90s\"").andExpect(status().isOk());
            JsonNode tuned = model(QWEN4);
            assertSettings(tuned, 6144, "90s", "Q4_K_M");
            for (String wrong : List.of("\"numCtx\":100", "\"keepAlive\":\"2 minuti\"", "\"keepAlive\":\"5m; rm\"",
                    "\"quantization\":\"<script>\"")) {
                tune(tuned, wrong).andExpect(status().isBadRequest());
            }
        } finally {
            tune(model(QWEN4), "\"numCtx\":8192,\"keepAlive\":\"2m\"").andExpect(status().isOk());
        }
    }

    @Test
    void aRunSendsTheSettingsOfItsModelAndAModelWithoutThemSendsNone() throws Exception {
        long small = agent("P35 Small Bot", QWEN4);
        EngineClient.Request local = requestOf(runAndWait(task("P35 Una task", small)));
        assertThat(local.model()).isEqualTo(QWEN4);
        assertThat(local.contextWindow()).isEqualTo(8192);
        assertThat(local.keepAlive()).isEqualTo("2m");

        long echo = agent("P35 Echo Bot", "echo:default");
        EngineClient.Request plain = requestOf(runAndWait(task("P35 Un'altra task", echo)));
        assertThat(plain.contextWindow()).isNull();
        assertThat(plain.keepAlive()).isNull();
    }

    // ------------------------------------------------------------------ the route

    @Test
    void shortWorkGoesToTheSmallModelAndPlanningAndCodeToThe9b() throws Exception {
        // An agent with a model decides the model itself: the route is asked only without one.
        long none = agent("P35 Generalist", null);
        JsonNode summary = decision(task("P35 Riassumi il verbale della riunione", none));
        assertThat(summary.path("model").asString()).as("FAST, the smaller of the two: " + summary).isEqualTo(QWEN4);
        assertThat(summary.path("role").asString()).isEqualTo("FAST");
        assertThat(summary.path("reason").asString()).contains("compiti brevi").contains("riassum");

        assertThat(decision(task("P35 Classifica le segnalazioni per gravità", none)).path("model").asString()).isEqualTo(QWEN4);
        assertThat(decision(task("P35 Pianifica la roadmap del trimestre", none)).path("model").asString()).isEqualTo(QWEN9);
        assertThat(decision(task("P35 Implementa l'endpoint dei contatti", none)).path("model").asString()).isEqualTo(QWEN9);
        // Code and a check together: the heavier role wins -- quality before speed.
        assertThat(decision(task("P35 Verifica i test dell'API", none)).path("role").asString()).isEqualTo("CODER");
        // Nothing the catalog recognises: the general fallback, the 9B.
        assertThat(decision(task("P35 Scrivi un messaggio di benvenuto", none)).path("model").asString()).isEqualTo(QWEN9);
    }

    @Test
    void aRunWhoseAgentHasNoModelIsRoutedByTheCatalogWithItsSettings() throws Exception {
        long none = agent("P35 Generalist", null);
        EngineClient.Request routed = requestOf(runAndWait(task("P35 Riassumi le note del cliente", none)));
        assertThat(routed.model()).isEqualTo(QWEN4);
        assertThat(routed.contextWindow()).isEqualTo(8192);

        long run = runAndWait(task("P35 Implementa la ricerca", none));
        assertThat(requestOf(run).model()).isEqualTo(QWEN9);
        assertThat(requestOf(run).contextWindow()).isEqualTo(16384);
    }

    // ------------------------------------------------------------------ one at a time

    @Test
    void localModelsRunOneAtATimeWhileOthersDoNotWait() throws Exception {
        long first = agent("P35 First", QWEN4);
        long second = agent("P35 Second", QWEN9);
        long other = agent("P35 Other", "echo:default");
        engine.holdOnlyTheNextCall();
        long held = launch(task("P35 Prima task locale", first));
        engine.awaitEntered();

        long waiting = launch(task("P35 Seconda task locale", second));
        long elsewhere = launch(task("P35 Task non locale", other));
        assertThat(awaitFinished(elsewhere).path("status").asString()).as("a model that is not local does not wait")
                .isEqualTo("SUCCEEDED");
        Thread.sleep(500);
        assertThat(run(waiting).path("status").asString()).as("the second local run waits, queued").isEqualTo("QUEUED");
        assertThat(engine.requests().stream().filter(r -> r.model().equals(QWEN9))).isEmpty();

        engine.release();
        assertThat(awaitFinished(held).path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(awaitFinished(waiting).path("status").asString()).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------ helpers

    private static void assertSettings(JsonNode model, int numCtx, String keepAlive, String quantization) {
        assertThat(model.path("numCtx").asInt()).as(model.path("key").asString()).isEqualTo(numCtx);
        assertThat(model.path("keepAlive").asString()).as(model.path("key").asString()).isEqualTo(keepAlive);
        assertThat(model.path("quantization").asString()).as(model.path("key").asString()).isEqualTo(quantization);
    }

    private JsonNode model(String key) throws Exception {
        for (JsonNode m : json.readTree(mockMvc.perform(get("/api/catalog/models")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("models")) {
            if (m.path("key").asString().equals(key)) {
                return m;
            }
        }
        throw new AssertionError("no " + key + " in the catalog");
    }

    private org.springframework.test.web.servlet.ResultActions tune(JsonNode model, String fields) throws Exception {
        return mockMvc.perform(put("/api/catalog/models").param("key", model.path("key").asString())
                .header(HttpHeaders.IF_MATCH, "\"" + model.path("version").asLong() + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"role\":\"" + model.path("role").asString() + "\",\"lifecycle\":\""
                        + model.path("lifecycle").asString() + "\"," + fields + "}"));
    }

    private long agent(String name, String model) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"role\":\"Generalist\",\"specialization\":\"tutto\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        if (model != null) {
            jdbc.update("UPDATE agents SET model = ? WHERE id = ?", model, id);
        }
        return id;
    }

    private long task(String title, Long agentId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"" + title + "\",\"status\":\"OPEN\",\"priority\":\"MEDIUM\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        if (agentId != null) {
            mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                    .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        }
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode decision(long taskId) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/tasks/" + taskId + "/orchestration")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).path("model");
    }

    private long launch(long taskId) throws Exception {
        MvcResult launched = mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted()).andReturn();
        return json.readTree(launched.getResponse().getContentAsString()).path("id").asLong();
    }

    private long runAndWait(long taskId) throws Exception {
        long id = launch(taskId);
        awaitFinished(id);
        return id;
    }

    private JsonNode run(long runId) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/runs/" + runId)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode awaitFinished(long runId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = run(runId);
            if (run.path("status").asString().equals("SUCCEEDED") || run.path("status").asString().equals("FAILED")) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("run " + runId + " did not finish");
    }

    private EngineClient.Request requestOf(long runId) throws Exception {
        String correlation = run(runId).path("correlationId").asString();
        return engine.requests().stream().filter(r -> correlation.equals(r.correlationId())).findFirst()
                .orElseThrow(() -> new AssertionError("run " + runId + " never reached the engine"));
    }
}
