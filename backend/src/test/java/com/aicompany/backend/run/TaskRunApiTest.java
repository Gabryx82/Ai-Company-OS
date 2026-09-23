package com.aicompany.backend.run;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.RunFailures;
import com.aicompany.backend.run.execution.RunDispatcher;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ADR-016: an agent runs a task through the AI Engine, and the control plane keeps
 * the canonical record of what happened.
 *
 * <p>The one to read first is {@link #aRunIsExecutedAfterTheLaunchCommitsAndEndsWithTheEnginesAnswer()}:
 * it walks the whole path -- launch, commit, executor, engine, record.
 */
class TaskRunApiTest extends AbstractPostgresTest {

    private static final String PROBLEM = "urn:ai-company-os:problem:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentRepository agentRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedEngineClient engine;

    @Autowired
    private RunDispatcher dispatcher;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void clearEverything() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        agentRepository.deleteAll();
    }

    @AfterEach
    void releaseAnyHeldCall() {
        engine.release();
    }

    // ------------------------------------------------------------------
    // The whole path
    // ------------------------------------------------------------------

    @Test
    void aRunIsExecutedAfterTheLaunchCommitsAndEndsWithTheEnginesAnswer() throws Exception {

        Long agent = agent("Code Architect", "Software Engineer", "Backend architecture");
        Long taskId = task("Design the planner", "Three endpoints, one table", agent, null);
        engine.answer("The plan: ...");

        MvcResult launched = mockMvc.perform(launch(taskId, etagOfTask(taskId)))
                .andExpect(status().isAccepted())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andExpect(jsonPath("$.taskId").value(taskId))
                .andExpect(jsonPath("$.agentId").value(agent))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.requestedBy").value("operator"))
                .andReturn();

        long runId = idOf(launched);
        assertThat(launched.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/runs/" + runId);

        JsonNode run = awaitFinished(runId);
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("output").asString()).isEqualTo("The plan: ...");
        assertThat(run.get("finishReason").asString()).isEqualTo("stop");
        assertThat(run.get("servedModel").asString()).isEqualTo("echo:default");
        assertThat(run.get("inputTokens").asInt()).isEqualTo(10);
        assertThat(run.get("outputTokens").asInt()).isEqualTo(20);
        assertThat(run.get("startedAt").isNull()).isFalse();
        assertThat(run.get("finishedAt").isNull()).isFalse();
    }

    @Test
    void launchingAnOpenTaskStartsItAndASuccessfulRunDoesNotCompleteIt() throws Exception {

        Long taskId = task("Open work", null, agent("Backend", "Engineer", "jvm"), null);

        long runId = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andExpect(status().isAccepted())
                .andReturn());
        assertThat(statusOf(taskId)).isEqualTo("IN_PROGRESS");

        awaitFinished(runId);
        assertThat(statusOf(taskId))
                .as("human in the loop: the output is reviewed, and the operator completes the task")
                .isEqualTo("IN_PROGRESS");
    }

    @Test
    void theEngineIsAskedWithTheAgentsIdentityAndTheTasksContent() throws Exception {

        Long project = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long agent = agent("Database Specialist", "Database Engineer", "PostgreSQL and data modeling");
        Long taskId = task("Index the runs table", "Queries by task, newest first", agent, project);

        long runId = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn());
        JsonNode run = awaitFinished(runId);

        EngineClient.Request sent = engine.requests().getFirst();
        assertThat(sent.system()).contains("Database Specialist").contains("Database Engineer")
                .contains("PostgreSQL and data modeling");
        assertThat(sent.user()).contains("Index the runs table").contains("Queries by task, newest first")
                .contains("Priority: HIGH").contains("Project: Company OS");
        assertThat(sent.model()).as("no model asked for: the engine's default").isNull();
        assertThat(sent.correlationId()).isEqualTo(run.get("correlationId").asString()).startsWith("run-");
        assertThat(sent.metadata()).containsEntry("run_id", String.valueOf(runId))
                .containsEntry("task_id", String.valueOf(taskId));

        // What was sent is part of the record, verbatim.
        assertThat(run.get("systemPrompt").asString()).isEqualTo(sent.system());
        assertThat(run.get("userPrompt").asString()).isEqualTo(sent.user());
    }

    @Test
    void aModelCanBeAskedForAndIsForwardedAsIs() throws Exception {

        Long taskId = task("Local work", null, agent("Backend", "Engineer", "jvm"), null);

        long runId = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"ollama:llama3.2:3b\"}"))
                .andExpect(jsonPath("$.requestedModel").value("ollama:llama3.2:3b")).andReturn());

        awaitFinished(runId);
        assertThat(engine.requests().getFirst().model()).isEqualTo("ollama:llama3.2:3b");
    }

    @Test
    void aModelThatIsNotAModelIdIsAValidationFailure() throws Exception {

        Long taskId = task("t", null, agent("Backend", "Engineer", "jvm"), null);

        mockMvc.perform(launch(taskId, etagOfTask(taskId))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"model\":\"; DROP TABLE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.model").exists());
        assertThat(runCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Who can be asked, and when
    // ------------------------------------------------------------------

    @Test
    void workAlreadyInProgressCanBeRunAgain() throws Exception {

        Long taskId = task("Second attempt", null, agent("Backend", "Engineer", "jvm"), null);
        jdbc.update("UPDATE tasks SET status = 'IN_PROGRESS' WHERE id = ?", taskId);

        mockMvc.perform(launch(taskId, etagOfTask(taskId))).andExpect(status().isAccepted());
        assertThat(statusOf(taskId)).isEqualTo("IN_PROGRESS");
    }

    @Test
    void finishedWorkIsNotRunAgain() throws Exception {

        Long taskId = task("Done", null, agent("Backend", "Engineer", "jvm"), null);
        jdbc.update("UPDATE tasks SET status = 'DONE' WHERE id = ?", taskId);

        mockMvc.perform(launch(taskId, etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "finished-task-cannot-run"));
        assertThat(runCount()).isZero();
    }

    @Test
    void nobodyToRunItMeansNoRun() throws Exception {

        Long unassigned = task("Nobody", null, null, null);
        mockMvc.perform(launch(unassigned, etagOfTask(unassigned)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "unassigned-task-cannot-start"));

        Agent retired = new Agent("Retired", "Engineer", "jvm");
        retired.deactivate();
        Long inactive = task("Retired agent", null, agentRepository.saveAndFlush(retired).getId(), null);
        mockMvc.perform(launch(inactive, etagOfTask(inactive)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "inactive-agent-cannot-receive-tasks"));

        assertThat(runCount()).isZero();
        assertThat(statusOf(unassigned)).isEqualTo("OPEN");
    }

    @Test
    void aTaskInAnArchivedProjectIsNotRun() throws Exception {

        Long project = projectRepository.saveAndFlush(new Project("Frozen", null)).getId();
        Long taskId = task("Frozen", null, agent("Backend", "Engineer", "jvm"), project);
        jdbc.update("UPDATE projects SET status = 'ARCHIVED' WHERE id = ?", project);

        mockMvc.perform(launch(taskId, etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "archived-project-task-is-immutable"));
        assertThat(runCount()).isZero();
    }

    @Test
    void oneRunAtATimePerTask() throws Exception {

        Long taskId = task("One at a time", null, agent("Backend", "Engineer", "jvm"), null);
        engine.holdNextCall();

        long first = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn());
        engine.awaitEntered();

        mockMvc.perform(get("/api/runs/{id}", first))
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.startedAt").isNotEmpty());

        mockMvc.perform(launch(taskId, etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEM + "task-run-in-progress"));

        engine.release();
        awaitFinished(first);

        mockMvc.perform(launch(taskId, etagOfTask(taskId))).andExpect(status().isAccepted());
    }

    // ------------------------------------------------------------------
    // ADR-009 on the launch: it may move the task
    // ------------------------------------------------------------------

    @Test
    void aLaunchRequiresTheTasksTagAndRefusesAStaleOne() throws Exception {

        Long taskId = task("Tagged", null, agent("Backend", "Engineer", "jvm"), null);
        String stale = etagOfTask(taskId);

        mockMvc.perform(post("/api/tasks/{id}/runs", taskId)).andExpect(status().isPreconditionRequired());

        long runId = idOf(mockMvc.perform(launch(taskId, stale)).andExpect(status().isAccepted()).andReturn());
        awaitFinished(runId);

        mockMvc.perform(launch(taskId, stale)).andExpect(status().isPreconditionFailed());
        assertThat(runCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Every run ends, and says how
    // ------------------------------------------------------------------

    @Test
    void anEngineProblemIsRecordedWithTheEnginesOwnType() throws Exception {

        Long taskId = task("Unknown model", null, agent("Backend", "Engineer", "jvm"), null);
        engine.fail("urn:ai-company-os:engine:problem:unknown-model", "Ollama has no model 'nope'");

        JsonNode run = awaitFinished(idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn()));

        assertThat(run.get("status").asString()).isEqualTo("FAILED");
        assertThat(run.get("failureType").asString()).isEqualTo("urn:ai-company-os:engine:problem:unknown-model");
        assertThat(run.get("failureDetail").asString()).isEqualTo("Ollama has no model 'nope'");
        assertThat(run.get("output").isNull()).isTrue();
        assertThat(statusOf(taskId)).as("a failed run does not move the task").isEqualTo("IN_PROGRESS");
    }

    @Test
    void anUnreachableEngineIsRecordedAsSuch() throws Exception {

        Long taskId = task("No engine", null, agent("Backend", "Engineer", "jvm"), null);
        engine.fail(RunFailures.ENGINE_UNREACHABLE, "Nothing answered");

        JsonNode run = awaitFinished(idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn()));
        assertThat(run.get("failureType").asString()).isEqualTo(RunFailures.ENGINE_UNREACHABLE);
    }

    @Test
    void aBugInTheControlPlaneFailsTheRunAndLeaksNothing() throws Exception {

        Long taskId = task("Bug", null, agent("Backend", "Engineer", "jvm"), null);
        engine.explode();

        JsonNode run = awaitFinished(idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn()));
        assertThat(run.get("status").asString()).isEqualTo("FAILED");
        assertThat(run.get("failureType").asString()).isEqualTo(RunFailures.INTERNAL);
        assertThat(run.toString()).doesNotContain("SECRET-INTERNALS").doesNotContain("IllegalStateException");
    }

    @Test
    void aRefusalIsAnAnswerNotAFailure() throws Exception {

        Long taskId = task("Declined", null, agent("Backend", "Engineer", "jvm"), null);
        engine.answerWith(new EngineClient.Completion("", "refusal", "anthropic:claude-opus-5", 5, 0, 3));

        JsonNode run = awaitFinished(idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn()));
        assertThat(run.get("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(run.get("finishReason").asString()).isEqualTo("refusal");
    }

    @Test
    void aFailedRunDoesNotBlockTheNextOne() throws Exception {

        Long taskId = task("Retry", null, agent("Backend", "Engineer", "jvm"), null);
        engine.fail(RunFailures.ENGINE_TIMEOUT, "slow");
        awaitFinished(idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn()));

        JsonNode second = awaitFinished(idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId)))
                .andExpect(status().isAccepted()).andReturn()));
        assertThat(second.get("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void runsInterruptedByAStopAreFailedAtTheNextStart() throws Exception {

        Long agent = agent("Backend", "Engineer", "jvm");
        Long taskId = task("Interrupted", null, agent, null);
        jdbc.update("""
                INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id,
                                       requested_by, started_at)
                VALUES (?, ?, 'RUNNING', 's', 'u', 'run-crashed', 'operator', now())""", taskId, agent);

        dispatcher.recoverInterruptedRuns();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, failure_type FROM task_runs WHERE correlation_id = 'run-crashed'");
        assertThat(row).containsEntry("status", "FAILED").containsEntry("failure_type", RunFailures.INTERRUPTED);
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    @Test
    void theRunsOfATaskAreListedNewestFirst() throws Exception {

        Long taskId = task("History", null, agent("Backend", "Engineer", "jvm"), null);
        long first = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn());
        awaitFinished(first);
        long second = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn());
        awaitFinished(second);

        mockMvc.perform(get("/api/tasks/{id}/runs", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value(second))
                .andExpect(jsonPath("$[1].id").value(first));
    }

    @Test
    void unknownThingsAreNotFound() throws Exception {

        mockMvc.perform(get("/api/runs/{id}", 987654321L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM + "run-not-found"));
        mockMvc.perform(get("/api/tasks/{id}/runs", 987654321L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEM + "task-not-found"));
    }

    /** The run says who did it, not who holds the task today. */
    @Test
    void aRunKeepsTheAgentThatRanItAfterTheTaskIsReassigned() throws Exception {

        Long first = agent("First", "Engineer", "jvm");
        Long taskId = task("Handover", null, first, null);
        long runId = idOf(mockMvc.perform(launch(taskId, etagOfTask(taskId))).andReturn());
        awaitFinished(runId);

        Long second = agent("Second", "Engineer", "jvm");
        jdbc.update("UPDATE tasks SET agent_id = ? WHERE id = ?", second, taskId);

        mockMvc.perform(get("/api/runs/{id}", runId)).andExpect(jsonPath("$.agentId").value(first));
    }

    // ------------------------------------------------------------------
    // The database agrees with the lifecycle
    // ------------------------------------------------------------------

    @Test
    void theDatabaseRefusesARunWhoseOutcomeContradictsItsStatus() {

        Long agent = agent("Backend", "Engineer", "jvm");
        Long taskId = task("Constraint", null, agent, null);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id,
                                       requested_by, finished_at)
                VALUES (?, ?, 'SUCCEEDED', 's', 'u', 'c', 'operator', now())""", taskId, agent))
                .as("succeeded with no output")
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id,
                                       requested_by)
                VALUES (?, ?, 'PAUSED', 's', 'u', 'c', 'operator')""", taskId, agent))
                .as("outside the vocabulary")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theDatabaseHoldsAtMostOneUnfinishedRunPerTask() {

        Long agent = agent("Backend", "Engineer", "jvm");
        Long taskId = task("Index", null, agent, null);
        String insert = """
                INSERT INTO task_runs (task_id, agent_id, status, system_prompt, user_prompt, correlation_id,
                                       requested_by)
                VALUES (?, ?, 'QUEUED', 's', 'u', ?, 'operator')""";

        jdbc.update(insert, taskId, agent, "one");
        assertThatThrownBy(() -> jdbc.update(insert, taskId, agent, "two"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- helpers -----------------------------------------------------------

    private MockHttpServletRequestBuilder launch(Long taskId, String ifMatch) {
        return post("/api/tasks/{id}/runs", taskId).header(HttpHeaders.IF_MATCH, ifMatch);
    }

    private long idOf(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).get("id").asLong();
    }

    /** Polls the run until the executor has finished it; the executor is real and asynchronous. */
    private JsonNode awaitFinished(long runId) throws Exception {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            JsonNode run = json.readTree(mockMvc.perform(get("/api/runs/{id}", runId)).andReturn()
                    .getResponse().getContentAsString());
            String status = run.get("status").asString();
            if (status.equals("SUCCEEDED") || status.equals("FAILED")) {
                return run;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("run " + runId + " did not finish within 15 s");
    }

    private String etagOfTask(Long id) throws Exception {
        return mockMvc.perform(get("/api/tasks/{id}", id)).andExpect(status().isOk()).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
    }

    private Long agent(String name, String role, String specialization) {
        return agentRepository.saveAndFlush(new Agent(name, role, specialization)).getId();
    }

    private Long task(String title, String description, Long agentId, Long projectId) {
        Long id = taskRepository.saveAndFlush(new Task(title, description, TaskStatus.OPEN, TaskPriority.HIGH)).getId();
        jdbc.update("UPDATE tasks SET agent_id = ?, project_id = ? WHERE id = ?", agentId, projectId, id);
        return id;
    }

    private String statusOf(Long taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }

    private long runCount() {
        return jdbc.queryForObject("SELECT count(*) FROM task_runs", Long.class);
    }
}
