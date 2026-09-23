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
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code Task → Agent}, and the three domain answers of
 * ADR-010 made falsifiable.
 *
 * <p>Written before the implementation. On the baseline the route does not
 * exist, so most of these fail as 404 rather than as a wrong answer -- which is
 * honest about what they prove at that point: the shape, not yet the rules. The
 * rules are proved by the mutations recorded in {@code IMPLEMENTATION.md} §6.
 *
 * <p>The one to read first is
 * {@link #aTaskWhoseAgentWasDeactivatedCanStillBeReassigned()}. It is the whole
 * of D3: if it ever fails, the answer to "does deactivating an agent freeze its
 * tasks" has silently become the project one, and the recovery path the relation
 * exists for has closed.
 */
class TaskAgentAssignmentApiTest extends AbstractPostgresTest {

    private static final String TASK_ENTITY = "com.aicompany.backend.task.model.Task";

    private static final String INACTIVE_AGENT =
            "urn:ai-company-os:problem:inactive-agent-cannot-receive-tasks";
    private static final String FROZEN_TASK =
            "urn:ai-company-os:problem:archived-project-task-is-immutable";
    private static final String PRECONDITION_FAILED = "urn:ai-company-os:problem:precondition-failed";
    private static final String PRECONDITION_REQUIRED = "urn:ai-company-os:problem:precondition-required";

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
    private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;

    @BeforeEach
    void clearEverything() {
        // Tasks first: they reference both of the others, and the foreign keys say so.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        agentRepository.deleteAll();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    // ------------------------------------------------------------------
    // The happy path, and the shape. AC-1
    // ------------------------------------------------------------------

    @Test
    void aTaskIsAssignedToAnAgentAndSaysSo() throws Exception {

        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.agentId").value(agentId))
                .andExpect(jsonPath("$.id").value(taskId));

        mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andExpect(jsonPath("$.agentId").value(agentId));
    }

    /**
     * The unassigned case, asserted <em>next to</em> the assigned one on purpose.
     *
     * <p>On its own it would have been a test that passes for the wrong reason:
     * before the column existed, {@code $.agentId} was absent, and "absent" and
     * "null" are the same thing to a JSON path assertion. Pairing them means the
     * test can only pass when the field really is in the contract and really does
     * distinguish the two tasks.
     */
    @Test
    void anUnassignedTaskReportsNoAgentAndAnAssignedOneReportsIts() throws Exception {

        Long agentId = activeAgent("Backend");
        Long assigned = task("Assigned");
        Long unassigned = task("Unassigned");

        mockMvc.perform(assign(assigned, agentId, etagOfTask(assigned))).andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/{id}", assigned))
                .andExpect(jsonPath("$.agentId").value(agentId));

        mockMvc.perform(get("/api/tasks/{id}", unassigned))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Unassigned"))
                .andExpect(jsonPath("$.agentId").doesNotExist());
    }

    /** AC-11. A rejected agent means no task: the client never cleans up after us. */
    @Test
    void aTaskCanBeCreatedWithAnAgentAndIsNotCreatedAtAllIfTheAgentRefusesIt() throws Exception {

        Long active = activeAgent("Backend");

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH","agentId":%d}"""
                                .formatted(active)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.agentId").value(active));

        Long inactive = inactiveAgent("Retired");

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"never born","status":"OPEN","priority":"HIGH","agentId":%d}"""
                                .formatted(inactive)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(INACTIVE_AGENT));

        assertThat(titles())
                .as("the refused creation must not have left a task behind")
                .doesNotContain("never born");

        // The other refusal on the same path, asserted here because the symmetry
        // between creating with an agent and assigning one afterwards is worth
        // covering route by route rather than assumed.
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"never born either","status":"OPEN","priority":"HIGH","agentId":987654}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-not-found"));

        assertThat(titles()).doesNotContain("never born either");
    }

    // ------------------------------------------------------------------
    // D1 -- an inactive agent receives no work. I-1, AC-2
    // ------------------------------------------------------------------

    @Test
    void anInactiveAgentReceivesNoWork() throws Exception {

        Long agentId = inactiveAgent("Retired");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(INACTIVE_AGENT));

        assertThat(agentIdOf(taskId))
                .as("a refused assignment must not have been written")
                .isNull();
    }

    /** Reversible by the caller, which is why it is a 409 and not a 403. */
    @Test
    void activatingTheAgentMakesTheSameAssignmentSucceed() throws Exception {

        Long agentId = inactiveAgent("Retired");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/agents/{id}/activate", agentId)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isOk());
    }

    @Test
    void anUnknownAgentIsAnAgentNotFound() throws Exception {

        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, 987654L, etagOfTask(taskId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-not-found"));
    }

    /**
     * AC-4. Asking for the agent the task already has changes nothing, so there
     * is nothing to refuse -- and that stays true after the agent is switched
     * off. A client that re-states what it just read is not punished for a
     * deactivation that does not concern it.
     */
    @Test
    void reassigningToTheAgentItAlreadyHasIsANoOpEvenOnceThatAgentIsInactive() throws Exception {

        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId))).andExpect(status().isOk());

        mockMvc.perform(post("/api/agents/{id}/deactivate", agentId)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId));
    }

    // ------------------------------------------------------------------
    // D2 -- a frozen task is frozen for this too. I-2, AC-3
    // ------------------------------------------------------------------

    @Test
    void aTaskInAnArchivedProjectDoesNotChangeAgent() throws Exception {

        Long projectId = project("Company OS");
        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, etagOfTask(taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/{id}/archive", projectId)
                        .header(HttpHeaders.IF_MATCH, etagOfProject(projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(FROZEN_TASK));

        assertThat(agentIdOf(taskId)).isNull();

        // Reversible, and by the flow ADR-006 §2 already accepted: restore, write, archive.
        mockMvc.perform(post("/api/projects/{id}/restore", projectId)
                        .header(HttpHeaders.IF_MATCH, etagOfProject(projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isOk());
    }

    /**
     * AC-3, the deterministic half. Both refusals apply: the task is frozen
     * <em>and</em> the destination agent is switched off. The task wins, for the
     * reason ADR-006 §7 gave for source-before-destination -- this task is frozen
     * before anybody looks at who it is being given to.
     */
    @Test
    void whenBothRefusalsApplyTheFrozenTaskIsWhatIsReported() throws Exception {

        Long projectId = project("Company OS");
        Long agentId = inactiveAgent("Retired");
        Long taskId = task("Wire the planner");

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, etagOfTask(taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/{id}/archive", projectId)
                        .header(HttpHeaders.IF_MATCH, etagOfProject(projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(FROZEN_TASK));
    }

    // ------------------------------------------------------------------
    // D3 -- and this is where the domain diverges. I-3, I-4, AC-5, AC-6
    // ------------------------------------------------------------------

    /**
     * I-3, AC-5. Deactivation writes one row, its own. Asserted on the writes
     * themselves rather than on the values, because a cascade that wrote every
     * task back identically would pass a value comparison.
     *
     * <p>The positive control matters as much as the assertion: a counter that
     * never moves proves nothing about deactivation until something is shown to
     * move it.
     */
    @Test
    void deactivatingAnAgentWritesNoTaskRow() throws Exception {

        Long agentId = activeAgent("Backend");
        Long first = task("Wire the planner");
        Long second = task("Draw the graph");

        mockMvc.perform(assign(first, agentId, etagOfTask(first))).andExpect(status().isOk());
        mockMvc.perform(assign(second, agentId, etagOfTask(second))).andExpect(status().isOk());

        long writesBefore = taskUpdateCount();

        mockMvc.perform(post("/api/agents/{id}/deactivate", agentId)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId)))
                .andExpect(status().isOk());

        assertThat(taskUpdateCount() - writesBefore)
                .as("""
                    ADR-010 D3: agent lifecycle consistency is derived. One update against a \
                    task here means the cascade was materialised, and with it comes the \
                    bookkeeping that stops activate being an exact inverse.""")
                .isZero();

        mockMvc.perform(post("/api/agents/{id}/activate", agentId)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId)))
                .andExpect(status().isOk());

        assertThat(taskUpdateCount() - writesBefore)
                .as("neither direction of the cycle touches a task row")
                .isZero();

        // Positive control: the instrument is not dead.
        long beforeARealOne = taskUpdateCount();
        mockMvc.perform(assign(first, activeAgent("Frontend"), etagOfTask(first)))
                .andExpect(status().isOk());

        assertThat(taskUpdateCount() - beforeARealOne)
                .as("the statistic does move when a task really is written")
                .isPositive();
    }

    /**
     * I-4, AC-6. <strong>The whole of D3.</strong>
     *
     * <p>For projects the derived rule is "frozen for writes". For agents that
     * same rule would be actively harmful, and this test is what keeps it from
     * arriving by analogy: the moment an agent is switched off is exactly the
     * moment its work has to be able to move, and the only way out of a frozen
     * task would be to reactivate the agent -- undoing the reason it was switched
     * off in the first place.
     */
    @Test
    void aTaskWhoseAgentWasDeactivatedCanStillBeReassigned() throws Exception {

        Long retiring = activeAgent("Backend");
        Long successor = activeAgent("Frontend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, retiring, etagOfTask(taskId))).andExpect(status().isOk());

        mockMvc.perform(post("/api/agents/{id}/deactivate", retiring)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(retiring)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, successor, etagOfTask(taskId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(successor));

        assertThat(agentIdOf(taskId)).isEqualTo(successor);
    }

    /** A task pointing at an inactive agent is a legal state, and stays readable. */
    @Test
    void aTaskKeepsPointingAtAnAgentThatWasSwitchedOff() throws Exception {

        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId))).andExpect(status().isOk());
        mockMvc.perform(post("/api/agents/{id}/deactivate", agentId)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentId").value(agentId));
    }

    // ------------------------------------------------------------------
    // The precondition, on the new path. I-5, I-6, I-7
    // ------------------------------------------------------------------

    @Test
    void theNewPathRequiresAPreconditionLikeEveryOtherMutation() throws Exception {

        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(put("/api/tasks/{id}/agent", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"agentId":%d}""".formatted(agentId)))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value(PRECONDITION_REQUIRED));

        mockMvc.perform(assign(taskId, agentId, "\"999\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(PRECONDITION_FAILED));

        assertThat(agentIdOf(taskId))
                .as("neither refusal may have written anything")
                .isNull();
    }

    /**
     * I-6, AC-8, and the observable form of ADR-010 §4: <strong>one version, not
     * two</strong>.
     *
     * <p>{@code agent_id} and {@code project_id} are columns of the same row, so
     * a caller that changes one invalidates the tag of a caller about to change
     * the other. That is the point of not versioning the association separately:
     * with two counters both callers would get a 200 and the second would have
     * decided on a task it had not seen.
     */
    @Test
    void changingTheProjectConsumesTheTagForChangingTheAgent() throws Exception {

        Long projectId = project("Company OS");
        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        String bothRead = etagOfTask(taskId);

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, bothRead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk());

        mockMvc.perform(assign(taskId, agentId, bothRead))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(PRECONDITION_FAILED));

        assertThat(agentIdOf(taskId)).isNull();
    }

    /** And the same in the other direction: one row, one counter. */
    @Test
    void changingTheAgentConsumesTheTagForChangingTheProject() throws Exception {

        Long projectId = project("Company OS");
        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        String bothRead = etagOfTask(taskId);

        mockMvc.perform(assign(taskId, agentId, bothRead)).andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, bothRead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isPreconditionFailed());
    }

    /**
     * I-7, AC-9, rule P3. Assigning writes the task row and nothing else, so the
     * agent's tag must not move. If it did, two assignments to the same active
     * agent would start refusing each other -- a conflict the domain does not
     * have, and the reason ADR-006 §4 refused {@code OPTIMISTIC_FORCE_INCREMENT}.
     */
    @Test
    void assigningATaskDoesNotChangeTheAgentsEntityTag() throws Exception {

        Long agentId = activeAgent("Backend");
        Long first = task("Wire the planner");
        Long second = task("Draw the graph");

        String agentBefore = etagOfAgent(agentId);

        mockMvc.perform(assign(first, agentId, etagOfTask(first))).andExpect(status().isOk());
        mockMvc.perform(assign(second, agentId, etagOfTask(second))).andExpect(status().isOk());

        assertThat(etagOfAgent(agentId))
                .as("the agent row was not written, so its version must not have moved")
                .isEqualTo(agentBefore);
    }

    // ------------------------------------------------------------------
    // Reading back. AC-10
    // ------------------------------------------------------------------

    @Test
    void theTasksOfAnAgentAreListedOldestFirstAndScopedToIt() throws Exception {

        Long mine = activeAgent("Backend");
        Long other = activeAgent("Frontend");

        Long first = task("Wire the planner");
        Long second = task("Draw the graph");
        Long elsewhere = task("Not mine");

        mockMvc.perform(assign(first, mine, etagOfTask(first))).andExpect(status().isOk());
        mockMvc.perform(assign(second, mine, etagOfTask(second))).andExpect(status().isOk());
        mockMvc.perform(assign(elsewhere, other, etagOfTask(elsewhere))).andExpect(status().isOk());

        mockMvc.perform(get("/api/agents/{id}/tasks", mine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("Wire the planner"))
                .andExpect(jsonPath("$[1].title").value("Draw the graph"));
    }

    /**
     * "This agent has no tasks" and "there is no such agent" are different
     * answers, and a client acts differently on them.
     */
    @Test
    void theTasksOfAnUnknownAgentAreANotFoundAndNotAnEmptyList() throws Exception {

        mockMvc.perform(get("/api/agents/{id}/tasks", 987654L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-not-found"));
    }

    /** An inactive agent answers reads normally: deactivation restricts writes. */
    @Test
    void theTasksOfAnInactiveAgentStayReadable() throws Exception {

        Long agentId = activeAgent("Backend");
        Long taskId = task("Wire the planner");

        mockMvc.perform(assign(taskId, agentId, etagOfTask(taskId))).andExpect(status().isOk());
        mockMvc.perform(post("/api/agents/{id}/deactivate", agentId)
                        .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/agents/{id}/tasks", agentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
    }

    // --- helpers -----------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder assign(
            Long taskId, Long agentId, String ifMatch) {

        return put("/api/tasks/{id}/agent", taskId)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"agentId":%d}""".formatted(agentId));
    }

    private String etagOfTask(Long id) throws Exception {
        return etagOf("/api/tasks/" + id);
    }

    private String etagOfAgent(Long id) throws Exception {
        return etagOf("/api/agents/" + id);
    }

    private String etagOfProject(Long id) throws Exception {
        return etagOf("/api/projects/" + id);
    }

    private String etagOf(String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path)).andExpect(status().isOk()).andReturn();
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).as("%s must carry an entity-tag".formatted(path)).isNotNull();
        return etag;
    }

    private Long task(String title) {
        return taskRepository.saveAndFlush(new Task(title, null, TaskStatus.OPEN, TaskPriority.HIGH)).getId();
    }

    private Long project(String name) {
        return projectRepository.saveAndFlush(new Project(name, null)).getId();
    }

    private Long activeAgent(String name) {
        return agentRepository.saveAndFlush(new Agent(name, "Engineer", "jvm")).getId();
    }

    private Long inactiveAgent(String name) {
        Agent agent = new Agent(name, "Engineer", "jvm");
        agent.deactivate();
        return agentRepository.saveAndFlush(agent).getId();
    }

    /** Straight through JDBC: the committed row, not what a persistence context remembers. */
    private Long agentIdOf(Long taskId) {
        return jdbc.queryForObject("SELECT agent_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private java.util.List<String> titles() {
        return jdbc.queryForList("SELECT title FROM tasks", String.class);
    }

    private long taskUpdateCount() {
        return statistics.getEntityStatistics(TASK_ENTITY).getUpdateCount();
    }
}
