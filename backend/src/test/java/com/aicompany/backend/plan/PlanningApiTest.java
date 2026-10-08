package com.aicompany.backend.plan;

import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.support.AbstractPostgresTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Master Orchestrator's planning (ADR-021) and the Human-in-the-Loop gate it
 * puts in front of execution (ADR-022), end to end over HTTP.
 */
public class PlanningApiTest extends AbstractPostgresTest {

    public static final String PLAN = """
            Ecco il piano:
            ```json
            {"summary":"Un gestionale per officine.","stack":["Spring Boot","React"],
             "phases":[
               {"title":"Fondamenta","objective":"Scheletro e test","software":["intellij-junie"],
                "completionCriteria":["build verde"],
                "tasks":[
                  {"title":"Creare lo scheletro backend","objective":"Progetto Spring Boot che compila",
                   "agentRole":"Backend Engineer","software":["intellij-junie"],"tests":["contesto si avvia"],
                   "priority":"alta"},
                  {"title":"Configurare la CI","objective":"Pipeline verde","agentRole":"Nessuno che esiste"}]},
               {"title":"Funzionalità","objective":"Clienti e interventi",
                "tasks":[{"title":"CRUD clienti","objective":"API clienti","agentRole":"Backend Engineer"}]}
             ]}
            ```""";

    /** Stage 2 answers: the tasks of phase 1 and of phase 2, as the engine returns them. */
    static final String TASKS_OF_PHASE_1 = """
            {"tasks":[
              {"title":"Creare lo scheletro backend","objective":"Progetto Spring Boot che compila",
               "implementation":"Spring Initializr, poi un test di contesto","agentRole":"Backend Engineer",
               "software":["intellij-junie"],"tests":["contesto si avvia"],"completionCriteria":["build verde"],
               "priority":"HIGH"},
              {"title":"Configurare la CI","objective":"Pipeline verde","implementation":"GitHub Actions",
               "agentRole":"Nessuno che esiste","tests":["la pipeline gira"],"completionCriteria":["badge verde"],
               "priority":"MEDIUM"}]}""";
    static final String TASKS_OF_PHASE_2 = """
            {"tasks":[{"title":"CRUD clienti","objective":"API clienti","implementation":"Controller e servizio",
              "agentRole":"Backend Engineer","tests":["test API"],"completionCriteria":["CRUD completo"],
              "priority":"MEDIUM"}]}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScriptedEngineClient engine;

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

    @BeforeEach
    void projectWithAWorkspace() throws Exception {
        tasks.deleteAll();
        projects.deleteAll();
        agents.findAll().stream().filter(a -> a.getName().equals("Backend Bot")).forEach(agents::delete);
        mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Backend Bot\",\"role\":\"Backend Engineer\",\"specialization\":\"Spring Boot APIs\"}"));

        MvcResult created = mockMvc.perform(post("/api/projects").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Officina\"}")).andExpect(status().isCreated()).andReturn();
        projectId = Long.parseLong(created.getResponse().getHeader(HttpHeaders.LOCATION).replaceAll(".*/", ""));
        mockMvc.perform(put("/api/projects/" + projectId + "/profile").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectType\":\"FULL_STACK\",\"workspacePath\":\""
                                + folder.toString().replace("\\", "\\\\") + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + projectId + "/workspace")).andExpect(status().isOk());
    }

    /** The three answers of a staged generation: the phases, then the tasks of each of the two phases. */
    private void scriptPlan() {
        engine.answer(PLAN);
        engine.answer(TASKS_OF_PHASE_1);
        engine.answer(TASKS_OF_PHASE_2);
    }

    private void writeMasterPrompt() throws Exception {
        mockMvc.perform(put("/api/projects/" + projectId + "/files").param("path", "MASTER_PROMPT.md")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# Officina\\nGestione clienti e interventi.\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode generateAndWait() throws Exception {
        return awaitPlanRun(startGeneration());
    }

    private long startGeneration() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/projects/" + projectId + "/plan/generate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"ollama:qwen3.5:9b\"}"))
                .andExpect(status().isAccepted()).andReturn();
        return json.readTree(started.getResponse().getContentAsString()).path("id").asLong();
    }

    private JsonNode awaitPlanRun(long runId) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode run = json.readTree(mockMvc.perform(get("/api/plan-runs/" + runId))
                    .andReturn().getResponse().getContentAsString());
            if (!run.path("status").asString().equals("RUNNING")) {
                return run;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("the plan run did not finish");
    }

    @Test
    void theTemplateIsNotAMasterPrompt() throws Exception {
        mockMvc.perform(post("/api/projects/" + projectId + "/plan/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:master-prompt-missing"));
    }

    @Test
    void aGeneratedPlanBecomesPhasesTasksAndTheirDocuments() throws Exception {
        writeMasterPrompt();
        scriptPlan();

        JsonNode run = generateAndWait();

        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("phases").asInt()).isEqualTo(2);
        assertThat(run.path("tasks").asInt()).isEqualTo(3);

        // Staged (ADR-021 §2): the phases, then the tasks of each phase, each call constrained.
        assertThat(engine.requests()).hasSize(3);
        EngineClient.Request phases = engine.requests().get(0);
        assertThat(phases.responseFormat()).isEqualTo("json");
        assertThat(phases.responseSchema()).contains("\"phases\"").contains("\"minItems\": 2");
        assertThat(phases.model()).isEqualTo("ollama:qwen3.5:9b");
        assertThat(phases.user()).contains("Gestione clienti e interventi").contains("FULL_STACK");
        EngineClient.Request tasksOfPhase2 = engine.requests().get(2);
        assertThat(tasksOfPhase2.responseSchema()).contains("\"tasks\"");
        assertThat(tasksOfPhase2.system()).contains("Backend Engineer").contains("claude-code");
        assertThat(tasksOfPhase2.user()).contains("THE PHASE TO DETAIL NOW: 2. Funzionalità");

        mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andExpect(jsonPath("$.project.planStatus").value("DRAFT"))
                .andExpect(jsonPath("$.phases[0].approval").value("PENDING"))
                .andExpect(jsonPath("$.phases[0].tasks[0].code").value("TASK-001"))
                .andExpect(jsonPath("$.phases[0].tasks[0].priority").value("HIGH"))
                .andExpect(jsonPath("$.phases[0].tasks[0].documentPath").value("tasks/TASK-001.md"))
                .andExpect(jsonPath("$.phases[1].tasks[0].code").value("TASK-003"));

        assertThat(Files.readString(folder.resolve("tasks/TASK-001.md")))
                .contains("# TASK-001 — Creare lo scheletro backend")
                .contains("Esegui TASK-001 seguendo `tasks/TASK-001.md` e la governance in `AGENTS.md`.")
                .contains("Backend Bot");
        assertThat(Files.readString(folder.resolve("docs/phases/PHASE_2.md"))).contains("TASK-003");
        assertThat(Files.readString(folder.resolve("docs/IMPLEMENTATION_PLAN.md"))).contains("Un gestionale per officine");
        assertThat(Files.readString(folder.resolve(".aicos/plan.json"))).contains("\"priority\" : \"HIGH\"");
    }

    @Test
    void aTaskIsGivenTheAgentItsRoleNamesAndLeftUnassignedWhenNobodyFits() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        generateAndWait();

        JsonNode phases = json.readTree(mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andReturn().getResponse().getContentAsString()).path("phases");
        assertThat(phases.path(0).path("tasks").path(0).path("agentId").isNull()).isFalse();
        assertThat(phases.path(0).path("tasks").path(1).path("agentId").isNull())
                .as("'Nessuno che esiste' names no agent: the operator assigns it").isTrue();
    }

    @Test
    void anUnreadableAnswerFailsTheRunKeepsTheOutputAndCreatesNothing() throws Exception {
        writeMasterPrompt();
        engine.answer("Mi dispiace, non posso produrre JSON.");

        JsonNode run = generateAndWait();

        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("failureDetail").asString()).startsWith("plan-invalid");
        assertThat(run.path("output").asString()).contains("Mi dispiace");
        mockMvc.perform(get("/api/projects/" + projectId + "/plan")).andExpect(jsonPath("$.phases.length()").value(0));
    }

    @Test
    void anEngineThatFailsFailsTheRunWithItsReason() throws Exception {
        writeMasterPrompt();
        engine.fail("urn:ai-company-os:run-failure:engine-unreachable", "Nothing answered");

        JsonNode run = generateAndWait();

        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("failureDetail").asString()).contains("Nothing answered");
    }

    // --- PHASE 32 (TD-43): a generation can be cancelled -------------------------------

    private void awaitInterrupted() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!engine.interrupted() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
        assertThat(engine.interrupted()).as("the thread waiting on the engine was interrupted, not left to time out")
                .isTrue();
    }

    @Test
    void aGenerationCancelledWhileItWaitsStopsThereAndImportsNothing() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        engine.holdNextCall();
        long runId = startGeneration();
        engine.awaitEntered();

        mockMvc.perform(post("/api/plan-runs/{id}/cancel", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureDetail").value("cancelled: Annullata da operator"));
        awaitInterrupted();

        JsonNode run = awaitPlanRun(runId);
        assertThat(run.path("failureDetail").asString()).isEqualTo("cancelled: Annullata da operator");
        Thread.sleep(300);
        assertThat(engine.requests()).as("the answer that came back is not followed by the next stage").hasSize(1);
        mockMvc.perform(get("/api/projects/" + projectId + "/plan")).andExpect(jsonPath("$.phases.length()").value(0));

        // Finished: cancelling again answers it as it is; an unknown run is a 404; a new generation may start.
        mockMvc.perform(post("/api/plan-runs/{id}/cancel", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureDetail").value("cancelled: Annullata da operator"));
        mockMvc.perform(post("/api/plan-runs/{id}/cancel", 999_999L)).andExpect(status().isNotFound());
        engine.reset();
        scriptPlan();
        assertThat(generateAndWait().path("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void theFailureThatFollowsACancellationDoesNotReplaceIt() throws Exception {
        writeMasterPrompt();
        engine.holdNextCall();   // unscripted: the late answer is an echo, which is not a plan
        long runId = startGeneration();
        engine.awaitEntered();

        mockMvc.perform(post("/api/plan-runs/{id}/cancel", runId)).andExpect(status().isOk());
        awaitInterrupted();
        Thread.sleep(300);

        JsonNode run = awaitPlanRun(runId);
        assertThat(run.path("failureDetail").asString()).isEqualTo("cancelled: Annullata da operator");
        assertThat(run.path("output").isNull() || run.path("output").isMissingNode()).isTrue();
    }

    @Test
    void aGenerationCancelledDuringItsLastStageWritesNoPlan() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        engine.holdCallsAfter(2);   // the phases and the tasks of phase 1 answer; phase 2 is held
        long runId = startGeneration();
        engine.awaitEntered();

        mockMvc.perform(post("/api/plan-runs/{id}/cancel", runId)).andExpect(status().isOk());
        awaitInterrupted();
        Thread.sleep(300);

        JsonNode run = awaitPlanRun(runId);
        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("failureDetail").asString()).isEqualTo("cancelled: Annullata da operator");
        assertThat(engine.requests()).hasSize(3);
        mockMvc.perform(get("/api/projects/" + projectId + "/plan")).andExpect(jsonPath("$.phases.length()").value(0));
    }

    @Test
    void aPlanWrittenByAnExternalAgentIsImported() throws Exception {
        Files.writeString(folder.resolve(".aicos/plan.json"), PLAN.substring(PLAN.indexOf('{'), PLAN.lastIndexOf('}') + 1));

        mockMvc.perform(post("/api/projects/" + projectId + "/plan/import"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("IMPORT"))
                .andExpect(jsonPath("$.tasks").value(3));

        mockMvc.perform(get("/api/projects/" + projectId + "/plan/handoff"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString(".aicos/plan.json")));
    }

    @Test
    void anInvalidPlanFileIsA422NamingEveryProblem() throws Exception {
        Files.writeString(folder.resolve(".aicos/plan.json"),
                "{\"phases\":[{\"title\":\"\",\"tasks\":[]},{\"title\":\"ok\",\"tasks\":[{\"title\":\" \"}]}]}");

        mockMvc.perform(post("/api/projects/" + projectId + "/plan/import"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors['phases[0].title']").exists())
                .andExpect(jsonPath("$.errors['phases[0].tasks']").exists())
                .andExpect(jsonPath("$.errors['phases[1].tasks[0].title']").exists());
    }

    @Test
    void aTaskOfAnUnapprovedPhaseDoesNotStartNorRunUntilTheOperatorApprovesIt() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        generateAndWait();
        JsonNode phase = json.readTree(mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andReturn().getResponse().getContentAsString()).path("phases").path(0);
        long taskId = phase.path("tasks").path(0).path("id").asLong();
        String taskTag = mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post("/api/tasks/" + taskId + "/start").header(HttpHeaders.IF_MATCH, taskTag))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:phase-not-approved"));
        mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, taskTag)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:phase-not-approved"));

        mockMvc.perform(post("/api/phases/" + phase.path("id").asLong() + "/approve")
                        .header(HttpHeaders.IF_MATCH, "\"" + phase.path("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval").value("APPROVED"));

        mockMvc.perform(post("/api/tasks/" + taskId + "/start").header(HttpHeaders.IF_MATCH, taskTag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    /** PHASE 32 (TD-09): the other answer of a review -- changes requested, with the note, and the tasks still held. */
    @Test
    void aPhaseSentBackForChangesKeepsItsTasksFromStarting() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        generateAndWait();
        JsonNode phase = json.readTree(mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andReturn().getResponse().getContentAsString()).path("phases").path(0);
        long taskId = phase.path("tasks").path(0).path("id").asLong();

        mockMvc.perform(post("/api/phases/" + phase.path("id").asLong() + "/request-changes")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"split the second task\"}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(post("/api/phases/" + phase.path("id").asLong() + "/request-changes")
                        .header(HttpHeaders.IF_MATCH, "\"" + phase.path("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"split the second task\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approval").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.approvalNote").value("split the second task"));

        String taskTag = mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").header(HttpHeaders.IF_MATCH, taskTag))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:phase-not-approved"));
    }

    @Test
    void anUntouchedDraftIsReplaceableAndAnApprovedPlanIsLocked() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        generateAndWait();

        scriptPlan();
        assertThat(generateAndWait().path("status").asString())
                .as("a draft nobody touched can be regenerated").isEqualTo("SUCCEEDED");
        mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andExpect(jsonPath("$.phases[0].tasks[0].code").value("TASK-001"))
                .andExpect(jsonPath("$.phases.length()").value(2));

        String projectTag = mockMvc.perform(get("/api/projects/" + projectId)).andReturn().getResponse()
                .getHeader(HttpHeaders.ETAG);
        mockMvc.perform(post("/api/projects/" + projectId + "/plan/approve").param("approveAllPhases", "true")
                        .header(HttpHeaders.IF_MATCH, projectTag))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planStatus").value("APPROVED"));

        mockMvc.perform(post("/api/projects/" + projectId + "/plan/generate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:plan-locked"));
    }

    /**
     * A draft stops being replaceable the moment any of its work began -- here a
     * task started after its phase was approved, with the plan itself still DRAFT.
     * (Mutation M5 of the PHASE 8-14 block survived until this test existed.)
     */
    @Test
    void aDraftWhoseWorkBeganCannotBeReplaced() throws Exception {
        writeMasterPrompt();
        scriptPlan();
        generateAndWait();
        JsonNode phase = json.readTree(mockMvc.perform(get("/api/projects/" + projectId + "/plan"))
                .andReturn().getResponse().getContentAsString()).path("phases").path(0);
        mockMvc.perform(post("/api/phases/" + phase.path("id").asLong() + "/approve")
                .header(HttpHeaders.IF_MATCH, "\"" + phase.path("version").asLong() + "\"")).andExpect(status().isOk());
        long taskId = phase.path("tasks").path(0).path("id").asLong();
        String tag = mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(post("/api/tasks/" + taskId + "/start").header(HttpHeaders.IF_MATCH, tag)).andExpect(status().isOk());

        scriptPlan();
        JsonNode run = generateAndWait();

        assertThat(run.path("status").asString()).isEqualTo("FAILED");
        assertThat(run.path("failureDetail").asString()).startsWith("plan-locked");
        mockMvc.perform(get("/api/tasks/" + taskId)).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    @Test
    void aTaskCreatedOutsideAPlanIsUntouchedByTheGate() throws Exception {
        String agentId = json.readTree(mockMvc.perform(get("/api/agents")).andReturn().getResponse()
                .getContentAsString()).path(0).path("id").asString();
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Senza piano\",\"status\":\"OPEN\",\"priority\":\"LOW\"}"))
                .andExpect(status().isCreated()).andReturn();
        long taskId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        String tag = created.getResponse().getHeader(HttpHeaders.ETAG);
        tag = mockMvc.perform(put("/api/tasks/" + taskId + "/agent").header(HttpHeaders.IF_MATCH, tag)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        mockMvc.perform(post("/api/tasks/" + taskId + "/start").header(HttpHeaders.IF_MATCH, tag))
                .andExpect(status().isOk());
    }
}
