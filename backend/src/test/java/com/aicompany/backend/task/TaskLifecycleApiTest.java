package com.aicompany.backend.task;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-37: a task can move through its lifecycle, and only along the edges ADR-014
 * draws.
 *
 * <pre>
 *   OPEN ──start──▶ IN_PROGRESS ──complete──▶ DONE
 *    ▲                  │                       │
 *    └──────stop────────┘                       │
 *    └────────────────reopen────────────────────┘
 * </pre>
 *
 * <p>The one to read first is
 * {@link #aTaskNobodyHoldsCannotBeStarted()}: "somebody is working on it now" is
 * what {@code IN_PROGRESS} means (ADR-011), and a task with no agent has nobody.
 */
class TaskLifecycleApiTest extends AbstractPostgresTest {

    private static final String ILLEGAL = "urn:ai-company-os:problem:illegal-task-state-transition";
    private static final String UNASSIGNED = "urn:ai-company-os:problem:unassigned-task-cannot-start";
    private static final String INACTIVE_AGENT = "urn:ai-company-os:problem:inactive-agent-cannot-receive-tasks";
    private static final String FROZEN = "urn:ai-company-os:problem:archived-project-task-is-immutable";

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

    @BeforeEach
    void clearEverything() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        agentRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // The edges that exist
    // ------------------------------------------------------------------

    @Test
    void anAssignedTaskStartsCompletesAndSaysSo() throws Exception {

        Long taskId = task(TaskStatus.OPEN, activeAgent("Backend"), null);

        MvcResult started = mockMvc.perform(transition(taskId, "start", etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andReturn();

        mockMvc.perform(transition(taskId, "complete", started.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        assertThat(statusOf(taskId)).isEqualTo("DONE");
    }

    @Test
    void workInProgressCanBeStoppedAndGoesBackToOpen() throws Exception {

        Long taskId = task(TaskStatus.IN_PROGRESS, activeAgent("Backend"), null);

        mockMvc.perform(transition(taskId, "stop", etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void finishedWorkCanBeReopened() throws Exception {

        Long taskId = task(TaskStatus.DONE, null, null);

        mockMvc.perform(transition(taskId, "reopen", etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    /** The whole graph, walked twice: the cycle has no dead end. */
    @Test
    void theLifecycleIsACycleNotALine() throws Exception {

        Long taskId = task(TaskStatus.OPEN, activeAgent("Backend"), null);

        for (String step : List.of("start", "complete", "reopen", "start", "stop", "start", "complete")) {
            mockMvc.perform(transition(taskId, step, etagOfTask(taskId))).andExpect(status().isOk());
        }
        assertThat(statusOf(taskId)).isEqualTo("DONE");
    }

    // ------------------------------------------------------------------
    // The edges that do not exist
    // ------------------------------------------------------------------

    /**
     * Every pair (state, transition) that is not an edge, and each is the same
     * refusal. Written as a table so that adding an edge to the domain without
     * deciding it here turns this red.
     */
    @Test
    void everyTransitionThatIsNotAnEdgeIsAConflict() throws Exception {

        Long agent = activeAgent("Backend");

        String[][] notEdges = {
                {"OPEN", "complete"}, {"OPEN", "stop"}, {"OPEN", "reopen"},
                {"IN_PROGRESS", "start"}, {"IN_PROGRESS", "reopen"},
                {"DONE", "start"}, {"DONE", "complete"}, {"DONE", "stop"},
        };

        for (String[] notEdge : notEdges) {
            Long taskId = task(TaskStatus.valueOf(notEdge[0]), agent, null);

            mockMvc.perform(transition(taskId, notEdge[1], etagOfTask(taskId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(ILLEGAL));

            assertThat(statusOf(taskId)).as("%s refused, nothing written", (Object) notEdge).isEqualTo(notEdge[0]);
        }
    }

    /** Not idempotent, like archive (ADR-004 §4): the second call is a caller mistake worth exposing. */
    @Test
    void startingTwiceIsAConflictNotANoOp() throws Exception {

        Long taskId = task(TaskStatus.OPEN, activeAgent("Backend"), null);

        MvcResult first = mockMvc.perform(transition(taskId, "start", etagOfTask(taskId)))
                .andExpect(status().isOk()).andReturn();

        mockMvc.perform(transition(taskId, "start", first.getResponse().getHeader(HttpHeaders.ETAG)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(ILLEGAL));
    }

    // ------------------------------------------------------------------
    // Who is working on it
    // ------------------------------------------------------------------

    @Test
    void aTaskNobodyHoldsCannotBeStarted() throws Exception {

        Long taskId = task(TaskStatus.OPEN, null, null);

        mockMvc.perform(transition(taskId, "start", etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(UNASSIGNED));

        assertThat(statusOf(taskId)).isEqualTo("OPEN");
    }

    @Test
    void aTaskWhoseAgentIsInactiveCannotBeStarted() throws Exception {

        Long taskId = task(TaskStatus.OPEN, inactiveAgent("Retired"), null);

        mockMvc.perform(transition(taskId, "start", etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(INACTIVE_AGENT));

        assertThat(statusOf(taskId)).isEqualTo("OPEN");
    }

    /**
     * ADR-010 D3 holds after the lifecycle exists: an agent switched off while its
     * task is in progress does not stop, complete or freeze that task. The rule
     * about the agent is on {@code start} only -- the moment work is taken on.
     */
    @Test
    void workAlreadyInProgressIsNotTrappedByTheAgentBeingDeactivated() throws Exception {

        Long agent = activeAgent("Backend");
        Long taskId = task(TaskStatus.IN_PROGRESS, agent, null);
        deactivate(agent);

        mockMvc.perform(transition(taskId, "complete", etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        Long other = task(TaskStatus.IN_PROGRESS, agent, null);
        mockMvc.perform(transition(other, "stop", etagOfTask(other)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // ADR-006 §2: any future write to a task in an archived project is refused
    // ------------------------------------------------------------------

    @Test
    void aTaskInAnArchivedProjectDoesNotMoveThroughItsLifecycle() throws Exception {

        Long agent = activeAgent("Backend");
        Long project = project("Frozen");

        Long open = task(TaskStatus.OPEN, agent, project);
        Long inProgress = task(TaskStatus.IN_PROGRESS, agent, project);
        Long done = task(TaskStatus.DONE, agent, project);
        archive(project);

        for (Object[] attempt : new Object[][]{{open, "start"}, {inProgress, "complete"},
                {inProgress, "stop"}, {done, "reopen"}}) {
            Long taskId = (Long) attempt[0];
            mockMvc.perform(transition(taskId, (String) attempt[1], etagOfTask(taskId)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value(FROZEN));
        }

        assertThat(statusOf(open)).isEqualTo("OPEN");
        assertThat(statusOf(inProgress)).isEqualTo("IN_PROGRESS");
        assertThat(statusOf(done)).isEqualTo("DONE");
    }

    /** Frozen is decided before legality: the refusal names the thing the caller can fix. */
    @Test
    void aFrozenTaskIsToldItIsFrozenEvenWhenTheTransitionWouldAlsoBeIllegal() throws Exception {

        Long project = project("Frozen");
        Long taskId = task(TaskStatus.DONE, null, project);
        archive(project);

        mockMvc.perform(transition(taskId, "complete", etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(FROZEN));
    }

    // ------------------------------------------------------------------
    // ADR-009: the precondition is inherited, and it comes first
    // ------------------------------------------------------------------

    @Test
    void everyTransitionRequiresIfMatch() throws Exception {

        Long taskId = task(TaskStatus.OPEN, activeAgent("Backend"), null);

        for (String step : List.of("start", "complete", "stop", "reopen")) {
            mockMvc.perform(post("/api/tasks/{id}/{step}", taskId, step))
                    .andExpect(status().isPreconditionRequired());
        }
        assertThat(statusOf(taskId)).isEqualTo("OPEN");
    }

    @Test
    void aStaleCallerIsToldItIsStaleAndNotThatTheTransitionIsIllegal() throws Exception {

        Long taskId = task(TaskStatus.OPEN, activeAgent("Backend"), null);
        String stale = etagOfTask(taskId);

        mockMvc.perform(transition(taskId, "start", stale)).andExpect(status().isOk());

        // With the new state, "start" is illegal; with the old tag, the caller is stale.
        // Stale wins: it is what the caller has to fix first.
        mockMvc.perform(transition(taskId, "start", stale))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void anUnknownTaskIsNotFound() throws Exception {

        mockMvc.perform(transition(987654321L, "start", "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:task-not-found"));
    }

    /** Rule P3: a transition writes its own row, and nobody else's version moves. */
    @Test
    void aTransitionMovesTheTaskVersionAndNoOtherOne() throws Exception {

        Long agent = activeAgent("Backend");
        Long project = project("Home");
        Long taskId = task(TaskStatus.OPEN, agent, project);

        String agentTag = etagOf("/api/agents/" + agent);
        String projectTag = etagOf("/api/projects/" + project);
        String taskTag = etagOfTask(taskId);

        mockMvc.perform(transition(taskId, "start", taskTag)).andExpect(status().isOk());

        assertThat(etagOfTask(taskId)).isNotEqualTo(taskTag);
        assertThat(etagOf("/api/agents/" + agent)).isEqualTo(agentTag);
        assertThat(etagOf("/api/projects/" + project)).isEqualTo(projectTag);
    }

    // The concurrent case lives in TaskLifecycleConcurrencyTest, with a forced
    // interleaving. A version of it here, at a barrier, survived the removal of
    // the row lock: two HTTP calls at a barrier do not overlap on demand.

    // --- helpers -----------------------------------------------------------

    private MockHttpServletRequestBuilder transition(Long taskId, String step, String ifMatch) {
        return post("/api/tasks/{id}/{step}", taskId, step).header(HttpHeaders.IF_MATCH, ifMatch);
    }

    private String etagOfTask(Long id) throws Exception {
        return etagOf("/api/tasks/" + id);
    }

    private String etagOf(String path) throws Exception {
        String etag = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).as(path).isNotNull();
        return etag;
    }

    /** Seeded through SQL, so a test can start from any state without walking the graph to it. */
    private Long task(TaskStatus status, Long agentId, Long projectId) {
        Long id = taskRepository.saveAndFlush(new Task("Lifecycle", null, status, TaskPriority.HIGH)).getId();
        jdbc.update("UPDATE tasks SET agent_id = ?, project_id = ? WHERE id = ?", agentId, projectId, id);
        return id;
    }

    private Long project(String name) {
        return projectRepository.saveAndFlush(new Project(name, null)).getId();
    }

    private void archive(Long projectId) {
        jdbc.update("UPDATE projects SET status = 'ARCHIVED' WHERE id = ?", projectId);
    }

    private Long activeAgent(String name) {
        return agentRepository.saveAndFlush(new Agent(name, "Engineer", "jvm")).getId();
    }

    private Long inactiveAgent(String name) {
        Agent agent = new Agent(name, "Engineer", "jvm");
        agent.deactivate();
        return agentRepository.saveAndFlush(agent).getId();
    }

    private void deactivate(Long agentId) {
        jdbc.update("UPDATE agents SET status = 'INACTIVE' WHERE id = ?", agentId);
    }

    private String statusOf(Long taskId) {
        return jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId);
    }
}
