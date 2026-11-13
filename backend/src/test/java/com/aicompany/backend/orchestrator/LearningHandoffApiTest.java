package com.aicompany.backend.orchestrator;

import com.aicompany.backend.hitl.LessonFormat;
import com.aicompany.backend.hitl.lesson.LessonParser;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 37 (ADR-044, revises ADR-022 §3-4): what a handoff asks of the agent, as far
 * as learning goes. The code is the agent's to write in every case. When the operator
 * is learning the agent writes a lesson -- code, explained line by line -- and leaves
 * the project's files to the operator; when the operator cannot follow, the agent
 * works alone and leaves the lesson to go through after; a project that delegates
 * gets no lesson unless one is asked for.
 */
class LearningHandoffApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeHost host;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;

    @BeforeEach
    void anAgent() throws Exception {
        clean();
        host.reset();
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P37 Teacher\",\"role\":\"Backend Engineer\",\"specialization\":\"Java\"}"))
                .andExpect(status().isCreated()).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM task_handoff_reads WHERE handoff_id IN (SELECT h.id FROM task_handoffs h JOIN tasks t ON t.id = h.task_id WHERE t.title LIKE 'P37L %')");
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P37L %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P37L %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P37L %'");
        jdbc.update("DELETE FROM projects WHERE name LIKE 'Impara P37 %'");
        jdbc.update("DELETE FROM agents WHERE name = 'P37 Teacher'");
    }

    /** A task of a project at that level, assigned; returns {task id, project folder}. */
    private Object[] task(String level) throws Exception {
        Path workspace = Files.createDirectories(folder.resolve(level.toLowerCase()));
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Impara P37 " + level + "\"}")).andExpect(status().isCreated()).andReturn();
        long projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"" + level + "\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"P37L Il modello dei prestiti\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":"
                                + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return new Object[] {id, workspace};
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    /** Hands the task to Claude Code and returns the package it wrote. */
    private String packageOf(Object[] task, String learning) throws Exception {
        long taskId = (long) task[0];
        JsonNode result = json.readTree(mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs")
                        .header(HttpHeaders.IF_MATCH, tag(taskId)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"claude-code\"" + (learning == null ? "" : ",\"learning\":\"" + learning + "\"") + "}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
        return Files.readString(((Path) task[1]).resolve(result.path("handoff").path("documentPath").asString()));
    }

    private String recordedMode(Object[] task) {
        return jdbc.queryForObject("SELECT learning_mode FROM task_handoffs WHERE task_id = ? ORDER BY id DESC LIMIT 1",
                String.class, (long) task[0]);
    }

    @Test
    void whileTheOperatorLearnsTheAgentWritesALessonAndLeavesTheFilesToThem() throws Exception {
        Object[] task = task("GUIDED");

        String document = packageOf(task, null);

        assertThat(document).contains("## Livello di Human-in-the-Loop: GUIDED")
                .contains("il codice lo scrivi tu, nei file lo porta lui")
                .contains("## Lezione")
                .contains("**Non modificare i file del progetto.**")
                .contains("`lessons/TASK-")
                .contains(".lezione.md`")
                .contains("**riga per riga**")
                // The format the console reads, whole.
                .contains(LessonFormat.GUIDE);
        // What the package shows as the shape of a lesson is a lesson the console accepts.
        String example = document.substring(document.indexOf("````markdown") + "````markdown".length(), document.lastIndexOf("````"));
        assertThat(LessonParser.parse(example.stripIndent()).problems()).isEmpty();
        // The lesson comes before the closing instructions, and the outcome is still asked for.
        assertThat(document.indexOf("## Lezione")).isLessThan(document.indexOf("## Alla fine"));
        assertThat(recordedMode(task)).isEqualTo("TEACH");
    }

    @Test
    void whenTheOperatorCannotFollowTheAgentWorksAloneAndLeavesTheLesson() throws Exception {
        Object[] task = task("GUIDED");

        String document = packageOf(task, "RECAP");

        assertThat(document).contains("Per questa task l'operatore **non può seguire**")
                .contains("lavora in autonomia")
                .contains("lezione di ripasso")
                .contains("`(già scritto)`")
                .contains(LessonFormat.GUIDE)
                // The teaching contract is not the one of this handoff.
                .doesNotContain("**Non modificare i file del progetto.**");
        assertThat(recordedMode(task)).isEqualTo("RECAP");
    }

    @Test
    void aProjectThatDelegatesGetsNoLessonUnlessOneIsAskedFor() throws Exception {
        Object[] task = task("DELEGATED");

        String plain = packageOf(task, null);

        assertThat(plain).doesNotContain("## Lezione").doesNotContain("Formato della lezione")
                .contains("## Livello di Human-in-the-Loop: DELEGATED");
        assertThat(recordedMode(task)).isEqualTo("NONE");

        String taught = packageOf(task, "TEACH");

        assertThat(taught).contains("## Lezione").contains("**Non modificare i file del progetto.**");
        assertThat(recordedMode(task)).isEqualTo("TEACH");
    }

    @Test
    void aSupervisedProjectGetsTheLessonToGoThroughAfter() throws Exception {
        Object[] task = task("SUPERVISED");

        String document = packageOf(task, null);

        assertThat(document).contains("## Lezione").contains("lezione di ripasso");
        assertThat(recordedMode(task)).isEqualTo("RECAP");
    }

    @Test
    void aModeThatDoesNotExistIsRefusedBeforeAnythingIsWritten() throws Exception {
        Object[] task = task("GUIDED");
        long taskId = (long) task[0];

        mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\",\"learning\":\"MAGIC\"}"))
                .andExpect(status().isBadRequest());

        assertThat(((Path) task[1]).resolve(".aicos/handoffs")).doesNotExist();
        assertThat(host.launches()).isEmpty();
    }
}
