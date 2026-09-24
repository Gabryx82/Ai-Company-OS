package com.aicompany.backend.orchestrator;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.plan.PlanningApiTest;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.FakeHost;
import com.aicompany.backend.support.ScriptedEngineClient;
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

/** The Master Orchestrator's execution half (ADR-021 §3–6), over HTTP, against a FakeHost. */
class OrchestratorApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private FakeHost host;

    @Autowired
    private TaskRepository tasks;

    @Autowired
    private ProjectRepository projects;

    @Autowired
    private AgentRepository agents;

    @TempDir
    Path folder;

    private final JsonMapper json = JsonMapper.builder().build();
    private long projectId;
    private JsonNode phase1;

    @BeforeEach
    void aPlannedProject() throws Exception {
        tasks.deleteAll();
        projects.deleteAll();
        agents.findAll().stream().filter(a -> a.getName().equals("Backend Bot")).forEach(agents::delete);
        mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Backend Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"Spring Boot APIs\"}"));
        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Officina\"}")).andReturn();
        projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"projectType\":\"FULL_STACK\",\"autonomyLevel\":\"GUIDED\",\"workspacePath\":\""
                        + folder.toString().replace("\\", "\\\\") + "\"}")).andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectId + "/workspace")).andExpect(status().isOk());
        String plan = PlanningApiTest.PLAN;
        Files.writeString(folder.resolve(".aicos/plan.json"), plan.substring(plan.indexOf('{'), plan.lastIndexOf('}') + 1));
        mockMvc.perform(post("/api/projects/" + projectId + "/plan/import")).andExpect(status().isOk());
        phase1 = planPhase(0);
    }

    private JsonNode planPhase(int index) throws Exception {
        return json.readTree(mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andReturn().getResponse().getContentAsString()).path("phases").path(index);
    }

    private void approvePhase1() throws Exception {
        mockMvc.perform(post("/api/phases/" + phase1.path("id").asLong() + "/approve")
                        .header(HttpHeaders.IF_MATCH, "\"" + phase1.path("version").asLong() + "\""))
                .andExpect(status().isOk());
    }

    private long task(int index) {
        return phase1.path("tasks").path(index).path("id").asLong();
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    @Test
    void theDecisionNamesAgentSoftwareContextPromptAndTargetsWithReasons() throws Exception {
        approvePhase1();

        mockMvc.perform(get("/api/tasks/" + task(0) + "/orchestration"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("TASK-001"))
                .andExpect(jsonPath("$.agent.name").value("Backend Bot"))
                .andExpect(jsonPath("$.agent.assigned").value(true))
                .andExpect(jsonPath("$.model.model").value("echo:default"))
                .andExpect(jsonPath("$.software[0].key").value("intellij-junie"))
                .andExpect(jsonPath("$.software[0].reason").value("Indicato dal piano"))
                .andExpect(jsonPath("$.context[?(@.path == 'tasks/TASK-001.md')].exists").value(hasItem(true)))
                .andExpect(jsonPath("$.context[?(@.path == 'AGENTS.md')].exists").value(hasItem(true)))
                .andExpect(jsonPath("$.prompt").value(
                        "Esegui TASK-001 seguendo `tasks/TASK-001.md` e la governance in `AGENTS.md`."))
                .andExpect(jsonPath("$.targets[?(@.key == 'claude-code')].available").value(hasItem(true)))
                .andExpect(jsonPath("$.autonomyLevel").value("GUIDED"))
                .andExpect(jsonPath("$.blockers.length()").value(0));
    }

    @Test
    void anUnapprovedPhaseIsABlockerAndRefusesTheHandoffBeforeAnythingIsLaunched() throws Exception {
        mockMvc.perform(get("/api/tasks/" + task(0) + "/orchestration"))
                .andExpect(jsonPath("$.phaseApproved").value(false))
                .andExpect(jsonPath("$.blockers[0]").value(org.hamcrest.Matchers.containsString("non è approvata")));

        mockMvc.perform(post("/api/tasks/" + task(0) + "/handoffs").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:phase-not-approved"));

        assertThat(host.launches()).isEmpty();
        assertThat(folder.resolve(".aicos/handoffs/TASK-001-claude-code.md")).doesNotExist();
    }

    @Test
    void aHandoffToClaudeCodeWritesTheFileOpensTheTerminalInTheFolderAndStartsTheTask() throws Exception {
        approvePhase1();

        mockMvc.perform(post("/api/tasks/" + task(0) + "/handoffs").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.promptToClipboard").value(false))
                .andExpect(jsonPath("$.handoff.documentPath").value(".aicos/handoffs/TASK-001-claude-code.md"));

        assertThat(host.launches()).hasSize(1);
        assertThat(host.launches().getFirst().command()).endsWith("-d", folder.toString(), "claude",
                "Leggi .aicos/handoffs/TASK-001-claude-code.md ed esegui la task che descrive.");
        assertThat(Files.readString(folder.resolve(".aicos/handoffs/TASK-001-claude-code.md")))
                .contains("Esegui TASK-001").contains("GUIDED").contains("Backend Bot");
        assertThat(planPhase(0).path("status").asString()).isEqualTo("IN_PROGRESS");
        mockMvc.perform(get("/api/tasks/" + task(0) + "/handoffs"))
                .andExpect(jsonPath("$[0].target").value("claude-code"));
    }

    @Test
    void aHandoffToADesktopAgentOpensTheAppAndHandsThePromptToTheClipboard() throws Exception {
        approvePhase1();

        mockMvc.perform(post("/api/tasks/" + task(0) + "/handoffs").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"codex\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.promptToClipboard").value(true));

        assertThat(host.launches().getFirst().command())
                .containsExactly("C:\\Windows\\explorer.exe", "shell:AppsFolder\\OpenAI.Codex_2p2nqsd0c76g0!App");
    }

    @Test
    void onlyExecutionTargetsReceiveHandoffsAndTheTagIsRequired() throws Exception {
        approvePhase1();

        mockMvc.perform(post("/api/tasks/" + task(0) + "/handoffs")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(post("/api/tasks/" + task(0) + "/handoffs").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"postman\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.target").exists());
        assertThat(host.launches()).isEmpty();
    }

    @Test
    void theOperatorsReviewCompletesOrKeepsTheTaskAndIsRecorded() throws Exception {
        approvePhase1();
        mockMvc.perform(post("/api/tasks/" + task(0) + "/handoffs").header(HttpHeaders.IF_MATCH, tag(task(0)))
                .contentType(MediaType.APPLICATION_JSON).content("{\"target\":\"claude-code\"}"));

        mockMvc.perform(post("/api/tasks/" + task(0) + "/reviews").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verdict\":\"CHANGES_REQUESTED\",\"note\":\"mancano i test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("IN_PROGRESS"));

        mockMvc.perform(post("/api/tasks/" + task(0) + "/reviews").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"ACCEPTED\",\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.task.status").value("DONE"));

        mockMvc.perform(get("/api/tasks/" + task(0) + "/reviews"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].verdict").value("ACCEPTED"))
                .andExpect(jsonPath("$[1].note").value("mancano i test"));
        assertThat(planPhase(0).path("status").asString())
                .as("one of two tasks done: the phase is still in progress").isEqualTo("IN_PROGRESS");
    }

    @Test
    void acceptingWorkThatNeverStartedIsRefused() throws Exception {
        mockMvc.perform(post("/api/tasks/" + task(1) + "/reviews").header(HttpHeaders.IF_MATCH, tag(task(1)))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"verdict\":\"ACCEPTED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:illegal-task-state-transition"));
    }

    @Test
    void aRunOfAPlannedTaskCarriesItsFilesAndTheAutonomyLevel() throws Exception {
        approvePhase1();

        mockMvc.perform(post("/api/tasks/" + task(0) + "/runs").header(HttpHeaders.IF_MATCH, tag(task(0)))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());

        EngineClient.Request request = awaitRequest();
        assertThat(request.system()).contains("Human-in-the-Loop level of this project: GUIDED")
                .contains("You are Backend Bot, Backend Engineer in AI Company OS.");
        assertThat(request.user()).contains("--- tasks/TASK-001.md ---").contains("# TASK-001 — Creare lo scheletro backend")
                .contains("--- docs/phases/PHASE_1.md ---");
    }

    @Test
    void aRunOfATaskOutsideAPlanSendsExactlyThePromptOfPhase6() throws Exception {
        long agentId = agents.findAll().stream().filter(a -> a.getName().equals("Backend Bot")).findFirst()
                .orElseThrow().getId();
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Fuori piano\",\"description\":\"Solo testo\",\"status\":\"OPEN\",\"priority\":\"LOW\"}"))
                .andReturn();
        long taskId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        String tag = mockMvc.perform(put("/api/tasks/" + taskId + "/agent")
                        .header(HttpHeaders.IF_MATCH, created.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        EngineClient.Request request = awaitRequest();
        assertThat(request.system()).isEqualTo("""
                You are Backend Bot, Backend Engineer in AI Company OS.
                Specialization: Spring Boot APIs.
                You are working on exactly one task. Answer with the work product itself -- a concrete \
                plan, design or result that a human operator will review before the task is closed. \
                Be precise and concise. If the task cannot be done as written, say what is missing.""");
        assertThat(request.user()).isEqualTo("Task #" + taskId + ": Fuori piano\nPriority: LOW\nProject: none\n\nSolo testo");
    }

    /**
     * The same regression, for the case that matters most: a task that lives in a
     * project -- with a folder and an autonomy level -- but outside its plan.
     * (Mutation M4 survived the no-project version of this test alone.)
     */
    @Test
    void aRunOfAProjectTaskOutsideThePlanAlsoSendsThePromptOfPhase6() throws Exception {
        long agentId = agents.findAll().stream().filter(a -> a.getName().equals("Backend Bot")).findFirst()
                .orElseThrow().getId();
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Nel progetto\",\"description\":\"Senza piano\",\"status\":\"OPEN\","
                        + "\"priority\":\"LOW\",\"projectId\":" + projectId + "}"))
                .andExpect(status().isCreated()).andReturn();
        long taskId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        String tag = mockMvc.perform(put("/api/tasks/" + taskId + "/agent")
                        .header(HttpHeaders.IF_MATCH, created.getResponse().getHeader(HttpHeaders.ETAG))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}"))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isAccepted());

        EngineClient.Request request = awaitRequest();
        assertThat(request.system()).doesNotContain("Human-in-the-Loop").endsWith("say what is missing.");
        assertThat(request.user()).isEqualTo("Task #" + taskId + ": Nel progetto\nPriority: LOW\nProject: Officina\n\nSenza piano");
    }

    private EngineClient.Request awaitRequest() throws InterruptedException {
        for (int i = 0; i < 100 && engine.requests().isEmpty(); i++) {
            Thread.sleep(50);
        }
        assertThat(engine.requests()).isNotEmpty();
        return engine.requests().getLast();
    }
}
