package com.aicompany.backend.journal;

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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 37 (ADR-047): the 3D office sees an agent start, and each of its steps -- the moments
 * the control plane records and the diary the agent keeps -- and what it changed in the folder
 * since the task was handed to it, line by line.
 */
class AgentJournalApiTest extends AbstractPostgresTest {

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

    @org.springframework.beans.factory.annotation.Value("${aicos.home}")
    String home;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<Long> agentIds = new ArrayList<>();

    @BeforeEach
    void clean() {
        tasks.deleteAll();
        projects.deleteAll();
    }

    @AfterEach
    void removeTheAgents() {
        jdbc.update("DELETE FROM task_handoffs");
        for (Long id : agentIds) {
            jdbc.update("UPDATE tasks SET agent_id = NULL WHERE agent_id = ?", id);
            jdbc.update("DELETE FROM agents WHERE id = ?", id);
        }
    }

    private long agent(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"role\":\"Backend Engineer\",\"specialization\":\"Spring Boot APIs\"}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        agentIds.add(id);
        return id;
    }

    private long project(Path workspace) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rubrica " + System.nanoTime() + "\"}")).andReturn();
        long id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"SUPERVISED\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        return id;
    }

    private long task(long projectId, long agentId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Endpoint contatti\",\"description\":\"CRUD dei contatti\",\"status\":\"OPEN\","
                        + "\"priority\":\"HIGH\",\"projectId\":" + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private void handoff(long taskId) throws Exception {
        mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}")).andExpect(status().isAccepted());
    }

    private JsonNode read(String uri) throws Exception {
        return json.readTree(mockMvc.perform(get(uri)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void theOfficeSeesTheAgentStartAndEachStepItWritesInItsDiary() throws Exception {
        long agentId = agent("Diario Bot");
        long other = agent("Altro Bot");
        Path rubrica = folder.resolve("rubrica");
        long taskId = task(project(rubrica), agentId);
        String label = "TASK-" + taskId;

        handoff(taskId);

        // The package says how to keep the diary, and the diary is there, empty, for the agent to write in.
        String pkg = Files.readString(rubrica.resolve(".aicos/handoffs/" + label + "-claude-code.md"));
        assertThat(pkg).contains("## Mentre lavori: il diario").contains("`tasks/" + label + ".diario.md`")
                .contains("date -Iseconds");
        assertThat(pkg.indexOf("## Mentre lavori")).isLessThan(pkg.indexOf("## Alla fine"));
        Path diary = rubrica.resolve("tasks/" + label + ".diario.md");
        assertThat(Files.readString(diary)).contains("una riga per passo");

        JsonNode started = read("/api/journal?agentId=" + agentId);
        assertThat(started).hasSize(1);
        assertThat(started.get(0).path("kind").asString()).isEqualTo("DELIVERED");
        assertThat(started.get(0).path("text").asString()).startsWith("Consegnata a Claude Code").endsWith("la task è in corso");
        assertThat(started.get(0).path("task").asString()).isEqualTo(label);
        assertThat(started.get(0).path("agentId").asLong()).isEqualTo(agentId);

        // The agent works: a line per step, with its time; one without a time.
        OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Files.writeString(diary, Files.readString(diary)
                + "- " + now.plusSeconds(5) + " · Letto tasks/" + label + ".md\n"
                + "- " + now.plusSeconds(20) + " · Creato src/Contatti.java\n"
                + "- Eseguiti i test\n");
        JsonNode working = read("/api/journal?agentId=" + agentId);
        assertThat(working).extracting(e -> e.path("kind").asString())
                .containsExactly("STEP", "STEP", "STEP", "DELIVERED");
        assertThat(working).extracting(e -> e.path("text").asString()).startsWith("Eseguiti i test", "Creato src/Contatti.java",
                "Letto tasks/" + label + ".md");
        assertThat(working.get(1).path("at").asString()).isEqualTo(now.plusSeconds(20).toInstant().toString());
        assertThat(working.get(1).path("approximate").asBoolean()).isFalse();
        assertThat(working.get(0).path("approximate").asBoolean()).isTrue();

        // The outcome, then the operator's review.
        Files.writeString(rubrica.resolve("tasks/" + label + ".esito.md"), "CRUD fatto, 6 test verdi.\n");
        mockMvc.perform(post("/api/tasks/" + taskId + "/reviews").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"ACCEPTED\",\"note\":\"Bene\"}"))
                .andExpect(status().is2xxSuccessful());
        JsonNode done = read("/api/journal?agentId=" + agentId);
        assertThat(done).extracting(e -> e.path("kind").asString()).contains("OUTCOME", "ACCEPTED");
        assertThat(done).extracting(e -> e.path("text").asString())
                .contains("Ha scritto l'esito in tasks/" + label + ".esito.md", "Review: accettata, task completata — Bene");

        // One agent's journal is its own; the office's has everyone's.
        assertThat(read("/api/journal?agentId=" + other)).isEmpty();
        assertThat(read("/api/journal?limit=2")).hasSize(2);
        mockMvc.perform(get("/api/journal?limit=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/journal?limit=501")).andExpect(status().isBadRequest());
    }

    @Test
    void whatTheAgentChangedSinceTheHandoffFileByFileAndLineByLine() throws Exception {
        long agentId = agent("Modifiche Bot");
        Path rubrica = Files.createDirectories(folder.resolve("rubrica"));
        Files.createDirectories(rubrica.resolve("src"));
        Files.writeString(rubrica.resolve("src/App.java"), "class App {\n    int a = 1;\n}\n");
        Files.writeString(rubrica.resolve("note.txt"), "da togliere\n");
        Files.createDirectories(rubrica.resolve("node_modules/x"));
        Files.writeString(rubrica.resolve("node_modules/x/index.js"), "module.exports = 1;\n");
        long taskId = task(project(rubrica), agentId);

        // A copy left by a database made before, for a task with the same id, is not this task's.
        Path stale = Files.createDirectories(Path.of(home, "snapshots", "task-" + taskId, "1-old"));
        Files.writeString(stale.resolve("manifest.tsv"), "");
        Files.writeString(stale.resolve("handoff-1-0"), "");
        mockMvc.perform(get("/api/tasks/" + taskId + "/changes"))
                .andExpect(jsonPath("$.tracked").value(false)).andExpect(jsonPath("$.files.length()").value(0));

        handoff(taskId);
        JsonNode none = read("/api/tasks/" + taskId + "/changes");
        assertThat(none.path("tracked").asBoolean()).isTrue();
        assertThat(none.path("since").asString()).isNotBlank();
        assertThat(none.path("files")).as("what the handoff wrote is not the agent's work").isEmpty();

        // The agent works.
        Files.writeString(rubrica.resolve("src/App.java"), "class App {\n    int a = 2;\n    int b = 3;\n}\n");
        Files.writeString(rubrica.resolve("src/Contatti.java"), "class Contatti {\n}\n");
        Files.delete(rubrica.resolve("note.txt"));
        Files.write(rubrica.resolve("logo.png"), new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        Files.writeString(rubrica.resolve("node_modules/x/index.js"), "module.exports = 2;\n");
        Path diary = rubrica.resolve("tasks/TASK-" + taskId + ".diario.md");
        Files.writeString(diary, Files.readString(diary) + "- Modificato App.java\n");

        JsonNode changes = read("/api/tasks/" + taskId + "/changes");
        assertThat(changes.path("files")).extracting(f -> f.path("path").asString() + " " + f.path("status").asString()
                        + " +" + f.path("additions").asInt() + " -" + f.path("deletions").asInt())
                .containsExactlyInAnyOrder("src/App.java MODIFIED +2 -1", "src/Contatti.java ADDED +2 -0", "note.txt DELETED +0 -1");
        assertThat(changes.path("additions").asInt()).isEqualTo(4);
        assertThat(changes.path("deletions").asInt()).isEqualTo(2);

        JsonNode diff = read("/api/tasks/" + taskId + "/changes/file?path=src/App.java");
        assertThat(diff.path("status").asString()).isEqualTo("MODIFIED");
        JsonNode lines = diff.path("hunks").get(0).path("lines");
        assertThat(lines).extracting(l -> l.path("kind").asString() + "|" + l.path("text").asString()).containsExactly(
                "CONTEXT|class App {", "REMOVED|    int a = 1;", "ADDED|    int a = 2;", "ADDED|    int b = 3;", "CONTEXT|}");
        assertThat(lines.get(3).path("newNumber").asInt()).isEqualTo(3);

        mockMvc.perform(get("/api/tasks/" + taskId + "/changes/file?path=note.txt"))
                .andExpect(jsonPath("$.status").value("DELETED")).andExpect(jsonPath("$.hunks[0].lines[0].text").value("da togliere"));
        mockMvc.perform(get("/api/tasks/" + taskId + "/changes/file?path=AGENTS.md")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/" + taskId + "/changes/file?path=../../etc/passwd")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tasks/999999/changes")).andExpect(status().isNotFound());

        // A new handoff of the same task starts counting again from there.
        handoff(taskId);
        assertThat(read("/api/tasks/" + taskId + "/changes").path("files")).isEmpty();
    }
}
