package com.aicompany.backend.orchestrator;

import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
import com.aicompany.backend.sync.AgentPublications;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 37 (ADR-043 §2): a task handed to OpenClaw. An OpenClaw agent runs in the
 * gateway, in its own workspace -- not in the folder the terminal opens on -- so the
 * prompt names the project folder in full; the terminal opens {@code openclaw tui} on
 * a session of the task's own, addressed to the OpenClaw agent that stands for this
 * one when there is one, and to OpenClaw's default agent when there is not.
 */
class OpenClawHandoffApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private FakeHost host;

    @Autowired
    private AgentPublications publications;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private Path workspace;
    private long agentId;
    private long projectId;

    @BeforeEach
    void aProjectAndAnAgent() throws Exception {
        clean();
        host.reset();
        workspace = Files.createDirectories(folder.resolve("gestionale prestiti"));
        MvcResult agent = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"P37 Claw Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"SQL\"}"))
                .andExpect(status().isCreated()).andReturn();
        agentId = json.readTree(agent.getResponse().getContentAsString()).path("id").asLong();
        MvcResult project = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Claw P37\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(project.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"BACKEND\",\"autonomyLevel\":\"DELEGATED\",\"workspacePath\":\""
                        + workspace.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM task_handoff_reads WHERE handoff_id IN (SELECT h.id FROM task_handoffs h JOIN tasks t ON t.id = h.task_id WHERE t.title LIKE 'P37C %')");
        jdbc.update("DELETE FROM task_handoffs WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P37C %')");
        jdbc.update("DELETE FROM task_transitions WHERE task_id IN (SELECT id FROM tasks WHERE title LIKE 'P37C %')");
        jdbc.update("DELETE FROM tasks WHERE title LIKE 'P37C %'");
        jdbc.update("DELETE FROM projects WHERE name = 'Claw P37'");
        jdbc.update("DELETE FROM agents WHERE name = 'P37 Claw Bot'");
    }

    private long task() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"P37C Migrare i prestiti\",\"status\":\"OPEN\",\"priority\":\"HIGH\",\"projectId\":"
                                + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, tag(id))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private JsonNode handoff(long taskId) throws Exception {
        return json.readTree(mockMvc.perform(post("/api/tasks/" + taskId + "/handoffs").header(HttpHeaders.IF_MATCH, tag(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"openclaw\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode readinessItem(long taskId, String key) throws Exception {
        JsonNode readiness = json.readTree(mockMvc.perform(get("/api/tasks/" + taskId + "/readiness").param("target", "openclaw"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (JsonNode item : readiness.path("items")) {
            if (item.path("key").asString().equals(key)) {
                return item;
            }
        }
        throw new AssertionError("No readiness item '" + key + "' in " + readiness);
    }

    @Test
    void thePromptNamesTheFolderAndTheTerminalOpensTheTuiOnTheTasksOwnSession() throws Exception {
        long taskId = task();

        JsonNode result = handoff(taskId);

        String packagePath = result.path("handoff").path("documentPath").asString();
        assertThat(packagePath).startsWith(".aicos/handoffs/").endsWith("-openclaw.md");
        String prompt = result.path("prompt").asString();
        assertThat(prompt).isEqualTo("Lavora nella cartella " + workspace + ": leggi lì il file " + packagePath
                + " ed esegui la task che descrive.");
        // One argument of a command line Windows Terminal reads: nothing in it that it would parse.
        assertThat(prompt).doesNotContain("\"").doesNotContain(";").doesNotContain("\n");
        assertThat(Files.readString(workspace.resolve(packagePath))).contains(prompt).contains("OpenClaw (agenti)");

        List<LaunchPlan> launches = host.launches();
        assertThat(launches).hasSize(1);
        List<String> command = launches.getFirst().command();
        // wt.exe -d <folder> openclaw tui --session <key> --message <prompt>
        assertThat(command.subList(1, command.size())).containsExactly("-d", workspace.toString(), "openclaw", "tui",
                "--session", "aicos-task-" + taskId, "--message", prompt);
        assertThat(result.path("promptToClipboard").asBoolean()).isFalse();
    }

    @Test
    void anAgentThatStandsInOpenClawTakesItsOwnTasksThere() throws Exception {
        long taskId = task();
        // Not in OpenClaw yet: the check says who will work, and it does not stop the delivery.
        JsonNode before = readinessItem(taskId, "openclaw-agent");
        assertThat(before.path("status").asString()).isEqualTo("WARNING");
        assertThat(before.path("detail").asString()).contains("agente predefinito di OpenClaw");

        publications.record(agentId, "openclaw", "aicos-p37-claw-bot", "0".repeat(64), true, "test");

        JsonNode after = readinessItem(taskId, "openclaw-agent");
        assertThat(after.path("status").asString()).isEqualTo("OK");
        assertThat(after.path("detail").asString()).contains("aicos-p37-claw-bot");

        handoff(taskId);

        List<String> command = host.launches().getFirst().command();
        assertThat(command).containsSequence("--session", "agent:aicos-p37-claw-bot:aicos-task-" + taskId, "--message");
    }

    @Test
    void theModelIsOpenClawsAndThePackageDoesNotNameTheAgentsOwn() throws Exception {
        jdbc.update("UPDATE agents SET model = 'ollama:qwen3.5:9b' WHERE id = ?", agentId);
        long taskId = task();

        JsonNode result = handoff(taskId);

        String document = Files.readString(workspace.resolve(result.path("handoff").path("documentPath").asString()));
        assertThat(document).contains("- **Modello**: scelto nell'app o nella CLI").doesNotContain("qwen3.5:9b");
    }
}
