package com.aicompany.backend.task;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 (TD-38): who moved a task, when, and through what. Every path that
 * takes an edge of the lifecycle records it, in the transaction that takes it.
 */
class TaskTransitionHistoryApiTest extends AbstractPostgresTest {

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
        agents.findAll().stream().filter(a -> a.getName().equals("History Bot")).forEach(agents::delete);
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"History Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"Spring Boot APIs\"}"))
                .andExpect(status().isCreated()).andReturn();
        agentId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    /** The agent must not outlive the class: plan imports elsewhere route by role, and would pick it. */
    @AfterEach
    void removeTheAgent() {
        jdbc.update("DELETE FROM task_transitions");
        jdbc.update("DELETE FROM task_reviews");
        jdbc.update("DELETE FROM task_handoffs");
        jdbc.update("DELETE FROM task_runs");
        jdbc.update("UPDATE tasks SET agent_id = NULL WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM agents WHERE id = ?", agentId);
    }

    @Test
    void everyEdgeTakenThroughTheLifecycleRoutesIsRecordedWithWhoAndWhenTheMostRecentFirst() throws Exception {
        long taskId = task(project());

        move(taskId, "start");
        move(taskId, "stop");
        move(taskId, "start");
        move(taskId, "complete");
        move(taskId, "reopen");

        JsonNode history = history(taskId);
        assertThat(edges(history)).containsExactly("REOPEN", "COMPLETE", "START", "STOP", "START");
        JsonNode last = history.get(0);
        assertThat(last.path("from").asString()).isEqualTo("DONE");
        assertThat(last.path("to").asString()).isEqualTo("OPEN");
        assertThat(last.path("actor").asString()).isEqualTo("operator");
        assertThat(last.path("via").asString()).isEqualTo("API");
        assertThat(last.path("at").asString()).isNotBlank();
        assertThat(last.path("taskId").asLong()).isEqualTo(taskId);
    }

    @Test
    void aRefusedTransitionRecordsNothing() throws Exception {
        long taskId = task(project());

        // A stale tag (412) and an edge that does not leave this state (409): nothing moved.
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").header(HttpHeaders.IF_MATCH, "\"99\""))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(post("/api/tasks/" + taskId + "/complete").header(HttpHeaders.IF_MATCH, tag(taskId)))
                .andExpect(status().isConflict());

        assertThat(history(taskId)).isEmpty();
        mockMvc.perform(get("/api/tasks/999999/transitions")).andExpect(status().isNotFound());
    }

    @Test
    void aRunsLaunchAHandoffAndAReviewRecordTheirOwnPath() throws Exception {
        long project = project();

        long launched = task(project);
        mockMvc.perform(post("/api/tasks/" + launched + "/runs").header(HttpHeaders.IF_MATCH, tag(launched))
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());
        JsonNode byRun = history(launched);
        assertThat(edges(byRun)).containsExactly("START");
        assertThat(byRun.get(0).path("via").asString()).isEqualTo("RUN");
        assertThat(byRun.get(0).path("actor").asString()).isEqualTo("operator");

        long handedOff = task(project);
        mockMvc.perform(post("/api/tasks/" + handedOff + "/handoffs").header(HttpHeaders.IF_MATCH, tag(handedOff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"manual\"}"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/api/tasks/" + handedOff + "/reviews").header(HttpHeaders.IF_MATCH, tag(handedOff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"ACCEPTED\",\"note\":\"ok\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tasks/" + handedOff + "/reviews").header(HttpHeaders.IF_MATCH, tag(handedOff))
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"CHANGES_REQUESTED\",\"note\":\"no\"}"))
                .andExpect(status().isOk());

        JsonNode byHandoff = history(handedOff);
        assertThat(edges(byHandoff)).containsExactly("REOPEN", "COMPLETE", "START");
        assertThat(vias(byHandoff)).containsExactly("REVIEW", "REVIEW", "HANDOFF");
    }

    @Test
    void deletingATaskTakesItsHistoryAndThePreviewSaysSo() throws Exception {
        long taskId = task(project());
        move(taskId, "start");
        move(taskId, "stop");

        mockMvc.perform(get("/api/tasks/" + taskId + "/deletion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[?(@ =~ /.*storia della task \\(2 transizioni\\).*/)]").exists());
        mockMvc.perform(delete("/api/tasks/" + taskId).param("confirm", "Endpoint contatti")
                        .header(HttpHeaders.AUTHORIZATION, AdminSession.bearer(mockMvc)).header(HttpHeaders.IF_MATCH, tag(taskId)))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM task_transitions WHERE task_id = ?", Integer.class, taskId))
                .isZero();
    }

    /**
     * Only {@code TaskTransitions} takes an edge: a path that called {@code Task.apply}
     * on its own would move a task without a trace.
     */
    @Test
    void nothingButTaskTransitionsCallsTaskApply() throws IOException {
        Pattern call = Pattern.compile("\\b\\w+\\.apply\\((TaskTransition\\.|transition\\b)");
        List<String> offenders = new ArrayList<>();
        Path sources = Path.of("src/main/java");
        try (Stream<Path> files = Files.walk(sources)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith(Path.of("task/service/TaskTransitions.java"))) {
                    continue;
                }
                Matcher m = call.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    offenders.add(sources.relativize(file) + ": " + m.group());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    // --- helpers -------------------------------------------------------------------

    private long project() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Storia\"}")).andExpect(status().isCreated()).andReturn();
        long id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"workspacePath\":\""
                        + folder.resolve("storia").toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        return id;
    }

    private long task(long projectId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Endpoint contatti\",\"description\":\"CRUD\",\"status\":\"OPEN\","
                        + "\"priority\":\"HIGH\",\"projectId\":" + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private void move(long taskId, String edge) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/" + edge).header(HttpHeaders.IF_MATCH, tag(taskId)))
                .andExpect(status().isOk());
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode history(long taskId) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/tasks/" + taskId + "/transitions"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> edges(JsonNode history) {
        List<String> edges = new ArrayList<>();
        history.forEach(entry -> edges.add(entry.path("transition").asString()));
        return edges;
    }

    private static List<String> vias(JsonNode history) {
        List<String> vias = new ArrayList<>();
        history.forEach(entry -> vias.add(entry.path("via").asString()));
        return vias;
    }
}
