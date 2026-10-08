package com.aicompany.backend.orchestrator;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 (TD-66): no ACTIVE model has the role CODER since ADR-034; qwen3.5:9b,
 * a PLANNER measured better at code, also serves it -- so code work without an
 * agent goes to it as a CODER model, not as a fallback to PLANNER.
 */
class ModelRoleRoutingApiTest extends AbstractPostgresTest {

    private static final String QWEN = "ollama:qwen3.5:9b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AgentRepository agents;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void noAgentToChooseAModel() {
        // An agent with a model would decide the model itself: the role is asked only without one.
        tasks.deleteAll();
        projects.deleteAll();
        agents.deleteAll();
        engine.serves(QWEN);
    }

    @Test
    void codeWorkWithoutAnAgentGoesToTheModelThatAlsoServesCoder() throws Exception {
        JsonNode qwen = model(QWEN);
        assertThat(qwen.path("role").asString()).isEqualTo("PLANNER");
        assertThat(qwen.path("alsoServes").toString()).isEqualTo("[\"CODER\"]");

        long task = codeTask();
        JsonNode coder = json.readTree(mockMvc.perform(get("/api/tasks/" + task + "/orchestration"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("model");
        assertThat(coder.path("model").asString()).isEqualTo(QWEN);
        assertThat(coder.path("role").asString()).isEqualTo("CODER");
        assertThat(coder.path("reason").asString()).contains("serve anche il ruolo CODER").doesNotContain("ripiego");

        try {
            classify(qwen, "[]");
            JsonNode fallback = json.readTree(mockMvc.perform(get("/api/tasks/" + task + "/orchestration"))
                    .andReturn().getResponse().getContentAsString()).path("model");
            assertThat(fallback.path("role").asString()).as("without it, the old fallback").isEqualTo("PLANNER");
            assertThat(fallback.path("reason").asString()).contains("ripiego su PLANNER");
        } finally {
            classify(model(QWEN), "[\"CODER\"]");
        }
        assertThat(model(QWEN).path("alsoServes").toString()).isEqualTo("[\"CODER\"]");
    }

    private JsonNode model(String key) throws Exception {
        JsonNode list = json.readTree(mockMvc.perform(get("/api/catalog/models")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        for (JsonNode m : list.path("models")) {
            if (m.path("key").asString().equals(key)) {
                return m;
            }
        }
        throw new AssertionError("no " + key + " in the catalog: " + list);
    }

    private void classify(JsonNode model, String alsoServes) throws Exception {
        mockMvc.perform(put("/api/catalog/models").param("key", model.path("key").asString())
                        .header(HttpHeaders.IF_MATCH, "\"" + model.path("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"" + model.path("role").asString() + "\",\"lifecycle\":\""
                                + model.path("lifecycle").asString() + "\",\"alsoServes\":" + alsoServes + "}"))
                .andExpect(status().isOk());
    }

    private long codeTask() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Implementa l'endpoint dei contatti\",\"status\":\"OPEN\",\"priority\":\"MEDIUM\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }
}
