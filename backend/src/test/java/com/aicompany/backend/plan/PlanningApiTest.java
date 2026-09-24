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
class PlanningApiTest extends AbstractPostgresTest {

    static final String PLAN = """
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

    private void writeMasterPrompt() throws Exception {
        mockMvc.perform(put("/api/projects/" + projectId + "/files").param("path", "MASTER_PROMPT.md")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"# Officina\\nGestione clienti e interventi.\"}"))
                .andExpect(status().isOk());
    }

    private JsonNode generateAndWait() throws Exception {
        MvcResult started = mockMvc.perform(post("/api/projects/" + projectId + "/plan/generate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"ollama:qwen3.5:9b\"}"))
                .andExpect(status().isAccepted()).andReturn();
        long runId = json.readTree(started.getResponse().getContentAsString()).path("id").asLong();
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
        engine.answer(PLAN);

        JsonNode run = generateAndWait();

        assertThat(run.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.path("phases").asInt()).isEqualTo(2);
        assertThat(run.path("tasks").asInt()).isEqualTo(3);

        EngineClient.Request request = engine.requests().getLast();
        assertThat(request.responseFormat()).isEqualTo("json");
        assertThat(request.model()).isEqualTo("ollama:qwen3.5:9b");
        assertThat(request.system()).contains("Backend Engineer").contains("claude-code");
        assertThat(request.user()).contains("Gestione clienti e interventi").contains("FULL_STACK");

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
        engine.answer(PLAN);
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
        engine.answer(PLAN);
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

    @Test
    void anApprovedPlanIsLockedAndADraftWithWorkDoneIsLockedToo() throws Exception {
        writeMasterPrompt();
        engine.answer(PLAN);
        generateAndWait();

        engine.answer(PLAN);
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
