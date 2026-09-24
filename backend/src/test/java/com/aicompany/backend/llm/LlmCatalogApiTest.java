package com.aicompany.backend.llm;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Providers and models as separate catalogs, joined with the engine's live view (ADR-018). */
class LlmCatalogApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Test
    void providersAreTheirOwnCatalogIncludingTheIncompatibleOne() throws Exception {
        mockMvc.perform(get("/api/catalog/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].key", hasItems("ollama", "anthropic", "openrouter", "dwarfstar", "echo")))
                .andExpect(jsonPath("$[?(@.key == 'dwarfstar')].status").value("INCOMPATIBLE_HARDWARE"))
                .andExpect(jsonPath("$[?(@.key == 'openrouter')].status").value("DISABLED"));
    }

    @Test
    void aCataloguedModelCarriesItsRoleAndTheEnginesAvailability() throws Exception {
        mockMvc.perform(get("/api/catalog/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.engineReachable").value(true))
                .andExpect(jsonPath("$.models[?(@.key == 'echo:default')].engineAvailable").value(true))
                .andExpect(jsonPath("$.models[?(@.key == 'ollama:qwen3.5:9b')].role").value("PLANNER"))
                .andExpect(jsonPath("$.models[?(@.key == 'ollama:qwen3.5:9b')].engineAvailable").value(false))
                .andExpect(jsonPath("$.models[?(@.key == 'ollama:llama3.2:3b')].lifecycle").value("DEPRECATED"));
    }

    @Test
    void anEngineThatDoesNotAnswerLeavesAvailabilityUnknownNotFalse() throws Exception {
        engine.modelsUnavailable();

        mockMvc.perform(get("/api/catalog/models"))
                .andExpect(jsonPath("$.engineReachable").value(false))
                .andExpect(jsonPath("$.models[?(@.key == 'echo:default')].engineAvailable").value(
                        org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
    }

    @Test
    void theOperatorReclassifiesAModelUnderThePreconditionProtocol() throws Exception {
        String body = mockMvc.perform(get("/api/catalog/models")).andReturn().getResponse().getContentAsString();
        int at = body.indexOf("\"key\":\"ollama:gemma4:e4b\"");
        String version = body.substring(body.indexOf("\"version\":", at) + 10).split("[,}]")[0];

        mockMvc.perform(put("/api/catalog/models").param("key", "ollama:gemma4:e4b")
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VISION\",\"lifecycle\":\"ACTIVE\",\"notes\":\"reference analysis\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes").value("reference analysis"));

        mockMvc.perform(put("/api/catalog/models").param("key", "nope:x")
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"FAST\",\"lifecycle\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:model-not-found"));
    }
}
