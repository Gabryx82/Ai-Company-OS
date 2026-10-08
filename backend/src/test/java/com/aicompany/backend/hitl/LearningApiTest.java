package com.aicompany.backend.hitl;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.repository.TaskRepository;
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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 (TD-45; ADR-022 §5): the learning metrics of a project and the level
 * they suggest -- measured from the reviews given at the project's level, the
 * history of its tasks and the pieces left to the operator in its code. Never
 * applied: the level is the operator's choice.
 */
class LearningApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private JdbcTemplate jdbc;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;

    @BeforeEach
    void anAgent() throws Exception {
        tasks.deleteAll();
        projects.deleteAll();
        agents.findAll().stream().filter(a -> a.getName().equals("Learning Bot")).forEach(agents::delete);
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Learning Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"Spring Boot APIs\"}"))
                .andExpect(status().isCreated()).andReturn();
        agentId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    @AfterEach
    void removeTheAgent() {
        jdbc.update("DELETE FROM task_transitions");
        jdbc.update("DELETE FROM task_reviews");
        jdbc.update("UPDATE tasks SET agent_id = NULL WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM agents WHERE id = ?", agentId);
    }

    @Test
    void fiveTasksAcceptedAtTheFirstReviewSuggestTheNextLevelOnceTheOperatorsPiecesAreWritten() throws Exception {
        Path code = folder.resolve("officina");
        long project = project("GUIDED", code);
        Files.createDirectories(code.resolve("src"));
        Path service = code.resolve("src/ClientService.java");
        Files.writeString(service, "class ClientService {\n    // TODO(operatore): scrivi la validazione dell'email\n}\n");
        // The rules that ask for the marker mention it without being a piece; nor do dependencies count.
        Files.writeString(code.resolve("AGENTS.md"), "Lascia i pezzi come `// TODO(operatore): …`\n");
        Files.createDirectories(code.resolve("node_modules/lib"));
        Files.writeString(code.resolve("node_modules/lib/index.js"), "// TODO(operatore): not ours\n");

        for (int i = 0; i < 4; i++) {
            review(startedTask(project), "ACCEPTED");
        }
        JsonNode four = learning(project);
        assertThat(four.path("reviewedTasks").asInt()).isEqualTo(4);
        assertThat(four.path("suggestion").path("direction").asString()).isEqualTo("STAY");
        assertThat(four.path("suggestion").path("reasons").get(0).asString()).contains("servono 5").contains("finora 4");

        long fifth = startedTask(project);
        JsonNode review = review(fifth, "ACCEPTED");
        assertThat(review.path("review").path("autonomyLevel").asString()).as("the level is kept on the review")
                .isEqualTo("GUIDED");

        JsonNode withPiece = learning(project);
        assertThat(withPiece.path("firstPassAccepted").asInt()).isEqualTo(5);
        assertThat(withPiece.path("piecesOpen").asInt()).isEqualTo(1);
        assertThat(withPiece.path("pieces").get(0).path("path").asString()).isEqualTo("src/ClientService.java");
        assertThat(withPiece.path("pieces").get(0).path("line").asInt()).isEqualTo(2);
        assertThat(withPiece.path("suggestion").path("direction").asString()).isEqualTo("STAY");
        assertThat(withPiece.path("suggestion").path("reasons").toString()).contains("Restano 1 pezzi");

        Files.writeString(service, "class ClientService {\n    boolean valid(String email) { return email.contains(\"@\"); }\n}\n");
        JsonNode written = learning(project);
        assertThat(written.path("piecesOpen").asInt()).isZero();
        assertThat(written.path("suggestion").path("direction").asString()).isEqualTo("UP");
        assertThat(written.path("suggestion").path("level").asString()).isEqualTo("SUPERVISED");
        mockMvc.perform(get("/api/projects/" + project)).andExpect(jsonPath("$.autonomyLevel").value("GUIDED"));
    }

    @Test
    void onlyTheFirstReviewAtTheCurrentLevelCountsAndAChangeOfLevelStartsAgain() throws Exception {
        long project = project("GUIDED", folder.resolve("rubrica"));
        long task = startedTask(project);
        review(task, "CHANGES_REQUESTED");
        review(task, "ACCEPTED");
        move(task, "reopen");
        move(task, "start");
        move(task, "complete");

        JsonNode guided = learning(project);
        assertThat(guided.path("reviewedTasks").asInt()).isEqualTo(1);
        assertThat(guided.path("firstPassAccepted").asInt()).as("sent back first, accepted later: not first pass").isZero();
        assertThat(guided.path("changesRequested").asInt()).isEqualTo(1);
        assertThat(guided.path("operatorCompletions").asInt()).as("completed by hand from the console").isEqualTo(1);

        setLevel(project, "SUPERVISED");
        JsonNode supervised = learning(project);
        assertThat(supervised.path("level").asString()).isEqualTo("SUPERVISED");
        assertThat(supervised.path("reviewedTasks").asInt()).isZero();
        assertThat(supervised.path("suggestion").path("reasons").get(0).asString()).contains("servono 8");
    }

    @Test
    void threeOfTheLastFourSentBackSuggestThePreviousLevel() throws Exception {
        long project = project("DELEGATED", folder.resolve("magazzino"));
        for (String verdict : new String[] {"ACCEPTED", "CHANGES_REQUESTED", "CHANGES_REQUESTED", "CHANGES_REQUESTED"}) {
            review(startedTask(project), verdict);
        }

        JsonNode learning = learning(project);
        assertThat(learning.path("suggestion").path("direction").asString()).isEqualTo("DOWN");
        assertThat(learning.path("suggestion").path("level").asString()).isEqualTo("SUPERVISED");
        assertThat(learning.path("suggestion").path("reasons").get(0).asString()).contains("3 sono state rimandate");
        mockMvc.perform(get("/api/projects/999999/learning")).andExpect(status().isNotFound());
    }

    // --- helpers -------------------------------------------------------------------

    private long project(String level, Path workspace) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Apprendimento " + level + "\"}")).andExpect(status().isCreated()).andReturn();
        long id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        Files.createDirectories(workspace);
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"" + level + "\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        return id;
    }

    private void setLevel(long project, String level) throws Exception {
        MvcResult read = mockMvc.perform(get("/api/projects/" + project)).andReturn();
        JsonNode body = json.readTree(read.getResponse().getContentAsString());
        mockMvc.perform(put("/api/projects/" + project + "/profile")
                .header(HttpHeaders.IF_MATCH, read.getResponse().getHeader(HttpHeaders.ETAG))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"" + level + "\",\"workspacePath\":\""
                        + body.path("workspacePath").asString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    private long startedTask(long project) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Pezzo\",\"status\":\"OPEN\",\"priority\":\"MEDIUM\",\"projectId\":" + project + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        move(id, "start");
        return id;
    }

    private void move(long taskId, String edge) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/" + edge).header(HttpHeaders.IF_MATCH, tag(taskId)))
                .andExpect(status().isOk());
    }

    private JsonNode review(long taskId, String verdict) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/tasks/" + taskId + "/reviews").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"" + verdict + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode learning(long project) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/projects/" + project + "/learning"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
}
