package com.aicompany.backend.orchestrator;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 17 (ADR-025 §4): Claude Code, Codex and the agentic IDEs without their
 * APIs -- any task, planned or not, with or without a project, is handed off
 * with its folder, its role, its context and its prompt prepared.
 */
class HandoffAnywhereApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FakeHost host;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AgentRepository agents;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long agentId;

    @BeforeEach
    void anAgent() throws Exception {
        tasks.deleteAll();
        projects.deleteAll();
        agents.findAll().stream().filter(a -> a.getName().equals("Handoff Bot")).forEach(agents::delete);
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Handoff Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"Spring Boot APIs\"}"))
                .andReturn();
        agentId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    /** The agent must not outlive the class: plan imports elsewhere route by role, and would pick it. */
    @org.junit.jupiter.api.AfterEach
    void removeTheAgent() {
        jdbc.update("DELETE FROM task_handoffs");
        jdbc.update("UPDATE tasks SET agent_id = NULL WHERE agent_id = ?", agentId);
        jdbc.update("DELETE FROM agents WHERE id = ?", agentId);
    }

    private long project() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Rubrica\"}")).andReturn();
        long id = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        // The folder is configured but not created: the handoff prepares it.
        mockMvc.perform(put("/api/projects/" + id + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"SUPERVISED\",\"workspacePath\":\""
                        + folder.resolve("rubrica").toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        return id;
    }

    private long task(Long projectId) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Endpoint contatti\",\"description\":\"CRUD dei contatti con validazione\","
                        + "\"status\":\"OPEN\",\"priority\":\"HIGH\"" + (projectId == null ? "" : ",\"projectId\":" + projectId) + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode handoff(long taskId, String target) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"" + target + "\"}"))
                .andExpect(status().isAccepted()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void aProjectTaskOutsideAPlanGetsItsFolderItsTaskDocumentAndThePackage() throws Exception {
        long taskId = task(project());
        Path rubrica = folder.resolve("rubrica");

        mockMvc.perform(get("/api/tasks/" + taskId + "/orchestration"))
                .andExpect(jsonPath("$.targets[?(@.key == 'claude-code')].available").value(hasItem(true)))
                .andExpect(jsonPath("$.targets[?(@.key == 'engine')].recommended").value(hasItem(true)));

        JsonNode result = handoff(taskId, "claude-code");

        assertThat(rubrica.resolve("AGENTS.md")).exists();
        String taskDoc = Files.readString(rubrica.resolve("tasks/TASK-" + taskId + ".md"));
        assertThat(taskDoc).contains("Endpoint contatti").contains("CRUD dei contatti con validazione").contains("Handoff Bot");
        String pkg = Files.readString(rubrica.resolve(".aicos/handoffs/TASK-" + taskId + "-claude-code.md"));
        assertThat(pkg).contains("Cartella di lavoro").contains("Handoff Bot").contains("SUPERVISED")
                .contains("tasks/TASK-" + taskId + ".md").contains("Claude Code");
        assertThat(result.path("prompt").asString()).isEqualTo("Leggi .aicos/handoffs/TASK-" + taskId + "-claude-code.md ed esegui la task che descrive.");
        assertThat(result.path("delivery").asString()).isEqualTo("CLI_PROMPT");
        assertThat(result.path("task").path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(host.launches()).hasSize(1);
        assertThat(host.launches().getFirst().command()).contains("claude", result.path("prompt").asString());
    }

    @Test
    void aTaskWithoutAProjectIsPreparedInAnInboxFolder() throws Exception {
        long taskId = task(null);

        JsonNode result = handoff(taskId, "opencode");

        Path inbox = Path.of(result.path("folder").asString());
        assertThat(inbox.getFileName().toString()).isEqualTo("task-" + taskId);
        assertThat(inbox.resolve("TASK.md")).exists();
        assertThat(Files.readString(inbox.resolve("AGENTS.md"))).contains("Questa cartella è stata preparata da AI Company OS");
        assertThat(inbox.resolve(".aicos/handoffs/TASK-" + taskId + "-opencode.md")).exists();
        assertThat(host.launches().getFirst().command()).contains("--prompt");
    }

    @Test
    void anIdeGetsItsRulesFileButAFileTheOperatorWroteIsNeverOverwritten() throws Exception {
        long projectId = project();
        long first = task(projectId);
        JsonNode result = handoff(first, "intellij-junie");
        Path rubrica = folder.resolve("rubrica");
        assertThat(result.path("written").toString()).contains(".junie/guidelines.md");
        assertThat(Files.readString(rubrica.resolve(".junie/guidelines.md"))).contains("AGENTS.md");

        Files.writeString(rubrica.resolve(".junie/guidelines.md"), "# Le mie regole\n");
        long second = task(projectId);
        JsonNode again = handoff(second, "intellij-junie");
        assertThat(again.path("written").toString()).doesNotContain(".junie/guidelines.md");
        assertThat(Files.readString(rubrica.resolve(".junie/guidelines.md"))).isEqualTo("# Le mie regole\n");
    }

    @Test
    void anAppWithoutFilesGetsTheWholePromptAndTheWebIsOpenedByTheConsoleNotLaunched() throws Exception {
        long projectId = project();
        JsonNode kimi = handoff(task(projectId), "kimi");
        assertThat(kimi.path("delivery").asString()).isEqualTo("APP_PASTE");
        assertThat(kimi.path("promptToClipboard").asBoolean()).isTrue();
        assertThat(kimi.path("fullPrompt").asString()).contains("Sei Handoff Bot").contains("CRUD dei contatti");

        int launchesBefore = host.launches().size();
        JsonNode gemini = handoff(task(projectId), "gemini");
        assertThat(gemini.path("delivery").asString()).isEqualTo("WEB_PASTE");
        assertThat(gemini.path("openUrl").asString()).startsWith("https://");
        assertThat(host.launches()).hasSize(launchesBefore);
    }

    @Test
    void theEngineIsNotAHandoffTarget() throws Exception {
        long taskId = task(null);
        mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"engine\"}"))
                .andExpect(status().isBadRequest());
        assertThat(host.launches()).isEmpty();
    }
}
