package com.aicompany.backend.binding;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** PHASE 16 (ADR-025): Agent -> Model -> Provider -> Execution Target, explicit and validated. */
class AgentBindingApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @AfterEach
    void removeWhatThisTestCreated() {
        jdbc.update("DELETE FROM agent_resources");
        jdbc.update("DELETE FROM agent_software");
        jdbc.update("UPDATE tasks SET agent_id = NULL WHERE agent_id IN (SELECT id FROM agents WHERE name LIKE 'Binding %' "
                + "OR name IN ('Claude Code Engineer', 'Codex Engineer'))");
        jdbc.update("DELETE FROM agents WHERE name LIKE 'Binding %' OR name IN ('Claude Code Engineer', 'Codex Engineer')");
    }

    private long agent(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"role\":\"Backend Engineer\",\"specialization\":\"API\"}"))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    private JsonNode configuration(long id) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/agents/" + id + "/configuration"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions configure(long id, JsonNode current, String model, String target, String systemPrompt)
            throws Exception {
        ObjectNode body = json.createObjectNode();
        body.put("role", current.path("role").asString());
        body.put("specialization", current.path("specialization").asString());
        body.put("systemPrompt", systemPrompt);
        body.put("description", current.path("description").isNull() ? null : current.path("description").asString());
        body.put("capabilities", String.join("\n", json.convertValue(current.path("capabilities"), String[].class)));
        body.put("responsibilities", current.path("responsibilities").isNull() ? null : current.path("responsibilities").asString());
        body.put("directives", String.join("\n", json.convertValue(current.path("directives"), String[].class)));
        body.put("model", model);
        body.put("executionTarget", target);
        return mockMvc.perform(put("/api/agents/" + id + "/configuration")
                .header(HttpHeaders.IF_MATCH, "\"" + current.path("version").asLong() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    @Test
    void theExecutionTargetsIncludeTheEngineTheCloudAppsAndTheAgenticIdes() throws Exception {
        mockMvc.perform(get("/api/execution-targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key").value(hasItems("engine", "claude-code", "codex", "opencode",
                        "antigravity-ide", "vscode-continue", "intellij-junie", "manual")))
                .andExpect(jsonPath("$[?(@.key == 'claude-code')].delivery").value(hasItems("CLI_PROMPT")))
                .andExpect(jsonPath("$[?(@.key == 'codex')].providers[0]").value(hasItems("chatgpt-subscription")));
    }

    @Test
    void aModelGoesOnlyWhereItCanRunAndTheChainIsShownWithItsProvider() throws Exception {
        long id = agent("Binding Backend");
        JsonNode fresh = configuration(id);
        assertThat(fresh.path("origin").asString()).isEqualTo("USER");
        assertThat(fresh.path("executionTarget").asString()).isEqualTo("engine");
        assertThat(fresh.path("defaults").isNull()).isTrue();

        // A local model on the engine: valid, provider Ollama.
        configure(id, fresh, "ollama:deepseek-coder-v2:16b", "engine", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.valid").value(true))
                .andExpect(jsonPath("$.binding.model.displayName").value("DeepSeek Coder V2 16B"))
                .andExpect(jsonPath("$.binding.provider.key").value("ollama"))
                .andExpect(jsonPath("$.binding.target.key").value("engine"))
                .andExpect(jsonPath("$.binding.summary").value("DeepSeek Coder V2 16B · Ollama (locale) → AI Engine (in AI Company OS)"));

        // A subscription model on the engine: refused, nothing changes.
        JsonNode now = configuration(id);
        configure(id, now, "claude-subscription:default", "engine", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:binding-invalid"));
        // A local model in Claude Code: refused too.
        configure(id, now, "ollama:qwen3.5:9b", "claude-code", null)
                .andExpect(status().isBadRequest());

        // The subscription model in its app: valid, no API involved.
        configure(id, now, "claude-subscription:default", "claude-code", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.valid").value(true))
                .andExpect(jsonPath("$.binding.provider.billing").value("SUBSCRIPTION"))
                .andExpect(jsonPath("$.binding.target.delivery").value("CLI_PROMPT"));

        // OpenCode can run the local models.
        configure(id, configuration(id), "ollama:qwen3.5:9b", "opencode", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binding.valid").value(true));
    }

    @Test
    void aTemplateAgentShowsItsDefaultsWhatAPersonChangedAndCanBeRestored() throws Exception {
        MvcResult installed = mockMvc.perform(post("/api/agent-templates/claude-code-engineer/install"))
                .andExpect(status().isOk()).andReturn();
        long id = json.readTree(installed.getResponse().getContentAsString()).get(0).path("agentId").asLong();

        JsonNode initial = configuration(id);
        assertThat(initial.path("origin").asString()).isEqualTo("TEMPLATE");
        assertThat(initial.path("executionTarget").asString()).isEqualTo("claude-code");
        assertThat(initial.path("model").asString()).isEqualTo("claude-subscription:default");
        assertThat(initial.path("modified")).isEmpty();
        assertThat(initial.path("defaults").path("systemPrompt").asString()).startsWith("Lavori in Claude Code");
        assertThat(initial.path("software").get(0).path("key").asString()).isEqualTo("claude-code");

        configure(id, initial, "claude-subscription:default", "claude-code", "Prompt riscritto dall'operatore.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modified").value(hasItems("systemPrompt")))
                .andExpect(jsonPath("$.customizedBy").value("operator"));

        JsonNode changed = configuration(id);
        mockMvc.perform(post("/api/agents/" + id + "/configuration/reset")
                        .header(HttpHeaders.IF_MATCH, "\"" + changed.path("version").asLong() + "\""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modified").isEmpty())
                .andExpect(jsonPath("$.systemPrompt").value(initial.path("systemPrompt").asString()));
    }

    @Test
    void aUserAgentHasNoInitialConfigurationToRestore() throws Exception {
        long id = agent("Binding Plain");
        mockMvc.perform(post("/api/agents/" + id + "/configuration/reset")
                        .header(HttpHeaders.IF_MATCH, "\"" + configuration(id).path("version").asLong() + "\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:no-baseline"));
    }

    @Test
    void anAgentThatWorksInClaudeCodeIsHandedOffNotRunThroughTheEngine() throws Exception {
        long id = agent("Binding Cloud");
        configure(id, configuration(id), "claude-subscription:default", "claude-code", null).andExpect(status().isOk());

        MvcResult task = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Binding task\",\"status\":\"OPEN\",\"priority\":\"MEDIUM\"}")).andReturn();
        long taskId = json.readTree(task.getResponse().getContentAsString()).path("id").asLong();
        String etag = task.getResponse().getHeader(HttpHeaders.ETAG);
        MvcResult assigned = mockMvc.perform(put("/api/tasks/" + taskId + "/agent").header(HttpHeaders.IF_MATCH, etag)
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + id + "}")).andExpect(status().isOk()).andReturn();
        String tag = assigned.getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-works-elsewhere"));
        // A subscription model asked for explicitly is refused as well: the engine cannot run it.
        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"chatgpt-subscription:default\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void theOldAgentEndpointCannotBindASubscriptionModelToTheEngineEither() throws Exception {
        long id = agent("Binding Legacy");
        MvcResult read = mockMvc.perform(get("/api/agents/" + id)).andReturn();
        mockMvc.perform(put("/api/agents/" + id).header(HttpHeaders.IF_MATCH, read.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Binding Legacy\",\"role\":\"Backend Engineer\",\"specialization\":\"API\","
                                + "\"model\":\"claude-subscription:default\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:binding-invalid"));
    }

    @Test
    void theEcosystemListsEveryAgentWithItsWholeChain() throws Exception {
        long id = agent("Binding Listed");
        mockMvc.perform(get("/api/ecosystem/bindings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].binding.target.key").value(hasItems("engine")));
    }
}
