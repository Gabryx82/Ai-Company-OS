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

    /**
     * PHASE 34: Claude Code in the desktop app takes a handoff as the CLI does -- the package
     * written, the app opened on the project folder with the prompt that reads it, through
     * Claude Desktop's own link; nothing to paste.
     */
    @Test
    void theDesktopAppOpensOnTheFolderWithThePromptThatReadsThePackage() throws Exception {
        long taskId = task(project());
        Path rubrica = folder.resolve("rubrica");

        mockMvc.perform(get("/api/tasks/" + taskId + "/orchestration"))
                .andExpect(jsonPath("$.targets[?(@.key == 'claude-desktop')].available").value(hasItem(true)))
                .andExpect(jsonPath("$.targets[?(@.key == 'claude-desktop')].delivery").value(hasItem("APP_LINK")));

        JsonNode result = handoff(taskId, "claude-desktop");

        String prompt = "Leggi .aicos/handoffs/TASK-" + taskId + "-claude-desktop.md ed esegui la task che descrive.";
        assertThat(Files.readString(rubrica.resolve(".aicos/handoffs/TASK-" + taskId + "-claude-desktop.md")))
                .contains("Handoff Bot").contains("Claude Code (app desktop)").contains("tasks/TASK-" + taskId + ".md");
        assertThat(Files.readString(rubrica.resolve("CLAUDE.md"))).as("Claude Code imports the governance by itself")
                .contains("\n@AGENTS.md\n");
        assertThat(result.path("prompt").asString()).isEqualTo(prompt);
        assertThat(result.path("delivery").asString()).isEqualTo("APP_LINK");
        // PHASE 36: in the composer through the link, and in the clipboard for when the app does not take it.
        assertThat(result.path("promptToClipboard").asBoolean()).as("the prompt is in the clipboard as well").isTrue();
        assertThat(result.path("task").path("status").asString()).isEqualTo("IN_PROGRESS");

        assertThat(host.launches()).hasSize(1);
        java.util.List<String> command = host.launches().getFirst().command();
        assertThat(command).hasSize(2);
        assertThat(command.getFirst()).endsWith("explorer.exe");
        assertThat(command.get(1)).startsWith("claude://code/new?folder=")
                .contains("folder=" + java.net.URLEncoder.encode(rubrica.toAbsolutePath().normalize().toString(),
                        java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"))
                .endsWith("&q=" + java.net.URLEncoder.encode(prompt, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        assertThat(jdbc.queryForObject("SELECT target FROM task_handoffs WHERE task_id = ?", String.class, taskId))
                .isEqualTo("claude-desktop");
    }

    /**
     * PHASE 34: a folder the operator brought (an existing repository) had no governance, and the
     * package sent the agent to an AGENTS.md that did not exist. Now every tool's file is written
     * where it is missing -- and only those: the operator's own file stays, nothing else is added.
     */
    @Test
    void aFolderTheOperatorBroughtGetsEveryToolsGovernanceAndKeepsItsOwn() throws Exception {
        long projectId = project();
        Path rubrica = Files.createDirectories(folder.resolve("rubrica"));
        Files.writeString(rubrica.resolve("CLAUDE.md"), "# Le mie istruzioni\n");

        JsonNode result = handoff(task(projectId), "codex");

        assertThat(result.path("written").toString()).contains("\"AGENTS.md\"", "\"GEMINI.md\"",
                "\".aicos/CONTEXT_MAP.md\"", "\".aicos/project.json\"").doesNotContain("\"CLAUDE.md\"");
        assertThat(Files.readString(rubrica.resolve("CLAUDE.md"))).isEqualTo("# Le mie istruzioni\n");
        assertThat(Files.readString(rubrica.resolve("AGENTS.md"))).contains("Rubrica").contains("SUPERVISED");
        assertThat(Files.readString(rubrica.resolve("GEMINI.md"))).contains("\n@./AGENTS.md\n");
        assertThat(rubrica.resolve("MASTER_PROMPT.md")).doesNotExist();
        assertThat(rubrica.resolve("docs")).doesNotExist();
        assertThat(rubrica.resolve("references")).doesNotExist();

        // A second handoff writes none of them again.
        assertThat(handoff(task(projectId), "claude-code").path("written").toString())
                .doesNotContain("\"AGENTS.md\"", "\"GEMINI.md\"", "\"CLAUDE.md\"");
    }

    @Test
    void aTaskWithoutAProjectIsPreparedInAnInboxFolder() throws Exception {
        long taskId = task(null);

        JsonNode result = handoff(taskId, "opencode");

        Path inbox = Path.of(result.path("folder").asString());
        assertThat(inbox.getFileName().toString()).isEqualTo("task-" + taskId);
        assertThat(inbox.resolve("TASK.md")).exists();
        assertThat(Files.readString(inbox.resolve("AGENTS.md"))).contains("Questa cartella è stata preparata da AI Company OS");
        assertThat(inbox.resolve("CLAUDE.md")).exists();
        assertThat(inbox.resolve("GEMINI.md")).exists();
        assertThat(inbox.resolve(".aicos/handoffs/TASK-" + taskId + "-opencode.md")).exists();
        assertThat(host.launches().getFirst().command()).contains("--prompt");
    }

    /** PHASE 32 (TD-55): what the agent wrote in the Esito of the task document comes back by itself. */
    @Test
    void theEsitoAnAgentWritesComesBackToTheConsole() throws Exception {
        long inProject = task(project());
        handoff(inProject, "claude-code");
        JsonNode none = json.readTree(mockMvc.perform(get("/api/tasks/" + inProject + "/outcomes"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(none).as("the placeholder is not an outcome").isEmpty();

        Path document = folder.resolve("rubrica/tasks/TASK-" + inProject + ".md");
        Files.writeString(document, Files.readString(document).replaceAll("(?s)## Esito.*",
                "## Esito\n\nCRUD dei contatti fatto, 6 test verdi. Resta la paginazione.\n"));
        JsonNode outcome = json.readTree(mockMvc.perform(get("/api/tasks/" + inProject + "/outcomes"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get(0);
        assertThat(outcome.path("text").asString()).isEqualTo("CRUD dei contatti fatto, 6 test verdi. Resta la paginazione.");
        assertThat(outcome.path("source").asString()).isEqualTo("sezione Esito di tasks/TASK-" + inProject + ".md");
        assertThat(outcome.path("at").asString()).isNotBlank();

        // Outside a project: the TASK.md of the inbox folder.
        long alone = task(null);
        Path inbox = Path.of(handoff(alone, "opencode").path("folder").asString());
        Files.writeString(inbox.resolve("TASK.md"), Files.readString(inbox.resolve("TASK.md"))
                .replaceAll("(?s)## Esito.*", "## Esito\n\nFatto.\n"));
        mockMvc.perform(get("/api/tasks/" + alone + "/outcomes"))
                .andExpect(jsonPath("$[0].text").value("Fatto."))
                .andExpect(jsonPath("$[0].source").value("sezione Esito di TASK.md"));
        mockMvc.perform(get("/api/tasks/999999/outcomes")).andExpect(status().isNotFound());
    }

    /**
     * PHASE 35 (ADR-041 §10, revises TD-55): the full outcome goes in its own file, beside the task
     * document, and the document's Esito keeps a summary with the link. The file is read first; when it
     * is missing or empty, the Esito section -- every document written before keeps working.
     */
    @Test
    void theOutcomeFileIsReadFirstAndWithoutItTheEsitoSection() throws Exception {
        long inProject = task(project());
        handoff(inProject, "claude-code");
        String label = "TASK-" + inProject;
        Path tasks = folder.resolve("rubrica/tasks");

        String pkg = Files.readString(folder.resolve("rubrica/.aicos/handoffs/" + label + "-claude-code.md"));
        String atTheEnd = pkg.substring(pkg.indexOf("## Alla fine"));
        assertThat(atTheEnd).contains("`tasks/" + label + ".esito.md`").contains("riassunto di poche righe")
                .contains("`tasks/" + label + ".md`");
        assertThat(Files.readString(tasks.resolve(label + ".md"))).as("the placeholder names the file")
                .contains(label + ".esito.md");

        Files.writeString(tasks.resolve(label + ".md"), Files.readString(tasks.resolve(label + ".md")).replaceAll("(?s)## Esito.*",
                "## Esito\n\nCRUD fatto, resta la paginazione. Dettagli in [" + label + ".esito.md](" + label + ".esito.md).\n"));
        Files.writeString(tasks.resolve(label + ".esito.md"), "# Esito\n\n- CRUD dei contatti\n- 6 test verdi\n- Resta la paginazione\n");
        JsonNode outcome = json.readTree(mockMvc.perform(get("/api/tasks/" + inProject + "/outcomes"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(outcome).hasSize(1);
        assertThat(outcome.get(0).path("text").asString()).isEqualTo("# Esito\n\n- CRUD dei contatti\n- 6 test verdi\n- Resta la paginazione");
        assertThat(outcome.get(0).path("source").asString()).isEqualTo("file tasks/" + label + ".esito.md");
        assertThat(outcome.get(0).path("documentPath").asString()).isEqualTo("tasks/" + label + ".esito.md");

        // An empty file is no outcome: the summary in the document is.
        Files.writeString(tasks.resolve(label + ".esito.md"), "  \n");
        mockMvc.perform(get("/api/tasks/" + inProject + "/outcomes"))
                .andExpect(jsonPath("$[0].source").value("sezione Esito di tasks/" + label + ".md"))
                .andExpect(jsonPath("$[0].text").value(org.hamcrest.Matchers.startsWith("CRUD fatto, resta la paginazione.")));

        // Outside a project: TASK.esito.md beside TASK.md, and the inbox governance says so.
        long alone = task(null);
        Path inbox = Path.of(handoff(alone, "opencode").path("folder").asString());
        assertThat(Files.readString(inbox.resolve("AGENTS.md"))).contains("`TASK.esito.md`");
        Files.writeString(inbox.resolve("TASK.esito.md"), "Fatto tutto, con i test.\n");
        mockMvc.perform(get("/api/tasks/" + alone + "/outcomes"))
                .andExpect(jsonPath("$[0].text").value("Fatto tutto, con i test."))
                .andExpect(jsonPath("$[0].source").value("file TASK.esito.md"));
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
