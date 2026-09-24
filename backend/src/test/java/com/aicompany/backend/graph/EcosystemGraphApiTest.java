package com.aicompany.backend.graph;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The Second Brain's graph (directive §17): every edge joins two nodes that exist. */
class EcosystemGraphApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void theGraphJoinsProjectsTasksAgentsModelsAndTheCatalogs() throws Exception {
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Grafo " + System.nanoTime() + "\"}")).andReturn();
        long projectId = json.readTree(project.getResponse().getContentAsString()).path("id").asLong();
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Grapher " + System.nanoTime() + "\",\"role\":\"Graph Engineer\","
                        + "\"specialization\":\"graphs\",\"model\":\"ollama:qwen3.5:9b\"}")).andReturn();
        long agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        MvcResult task = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Disegna il grafo\",\"status\":\"OPEN\",\"priority\":\"LOW\",\"projectId\":" + projectId + "}"))
                .andReturn();
        long taskId = json.readTree(task.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + taskId + "/agent").header("If-Match", task.getResponse().getHeader("ETag"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        mockMvc.perform(put("/api/agents/" + agentId + "/resources/graph-modeling")).andExpect(status().isNoContent());

        JsonNode graph = json.readTree(mockMvc.perform(get("/api/graph")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        Set<String> ids = new HashSet<>();
        graph.path("nodes").forEach(n -> ids.add(n.path("id").asString()));
        assertThat(ids).contains("project:" + projectId, "task:" + taskId, "agent:" + agentId,
                "model:ollama:qwen3.5:9b", "provider:ollama", "software:claude-code", "resource:graph-modeling");

        Set<String> edges = new HashSet<>();
        graph.path("edges").forEach(e -> {
            assertThat(ids).as("an edge never points to nothing").contains(e.path("source").asString(), e.path("target").asString());
            edges.add(e.path("source").asString() + " " + e.path("kind").asString() + " " + e.path("target").asString());
        });
        assertThat(edges).contains(
                "project:" + projectId + " contains task:" + taskId,
                "task:" + taskId + " assigned-to agent:" + agentId,
                "agent:" + agentId + " uses-model model:ollama:qwen3.5:9b",
                "model:ollama:qwen3.5:9b served-by provider:ollama",
                "agent:" + agentId + " equipped-with resource:graph-modeling");
    }
}
