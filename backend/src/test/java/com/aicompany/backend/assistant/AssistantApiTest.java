package com.aicompany.backend.assistant;

import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The console's quick assistant: it answers from the control plane's own state, only with a model
 * that costs nothing per call, and says what to do when there is none.
 */
class AssistantApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private TaskRepository tasks;

    @BeforeEach
    void cleanState() throws Exception {
        engine.reset();
        tasks.deleteAll();
        projects.deleteAll();
        mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cinema Shop\"}")).andExpect(status().isCreated());
    }

    @Test
    void aQuestionIsAnsweredByALocalModelFromTheEcosystemsState() throws Exception {
        engine.serves("ollama:qwen3:4b");
        engine.answer("  Hai un progetto attivo: Cinema Shop.  ");

        mockMvc.perform(post("/api/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Quanti progetti ho?\",\"history\":[{\"speaker\":\"USER\",\"text\":\"Ciao\"},"
                                + "{\"speaker\":\"ASSISTANT\",\"text\":\"Ciao, dimmi pure.\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Hai un progetto attivo: Cinema Shop."))
                .andExpect(jsonPath("$.model").value("ollama:qwen3:4b"));

        assertThat(engine.requests()).singleElement().satisfies(request -> {
            assertThat(request.model()).isEqualTo("ollama:qwen3:4b");
            assertThat(request.system()).contains("Progetti attivi: 1").contains("Cinema Shop");
            assertThat(request.user()).contains("Utente: Ciao").contains("Assistente: Ciao, dimmi pure.")
                    .endsWith("Utente: Quanti progetti ho?\n\nAssistente:");
        });
    }

    @Test
    void withoutAFreeModelTheQuestionIsRefusedAndNothingIsSent() throws Exception {
        // The scripted engine serves only the echo model and a billed one.
        mockMvc.perform(post("/api/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"Quanti progetti ho?\"}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(engine.requests()).isEmpty();
    }

    @Test
    void anEmptyQuestionIsRefused() throws Exception {
        mockMvc.perform(post("/api/assistant/ask").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }
}
