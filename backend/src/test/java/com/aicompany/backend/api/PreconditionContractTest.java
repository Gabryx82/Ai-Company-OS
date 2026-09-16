package com.aicompany.backend.api;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The precondition protocol of ADR-009, as an HTTP contract.
 *
 * <p>Written before the implementation and seen to fail without it. On the
 * baseline every mutation below answers 200 and the write lands: that is not a
 * missing feature, it is TD-28 and TD-30 being observable -- a caller writes over
 * somebody else's change and is told it succeeded.
 *
 * <h2>What this file does not cover</h2>
 *
 * <p>That the comparison happens <em>under the lock</em>. Two requests carrying
 * the same still-valid ETag would both pass a check made before L0 and then race,
 * and no sequential test can tell the two placements apart. That is
 * {@code PreconditionConcurrencyTest} and its mutation.
 */
class PreconditionContractTest extends AbstractPostgresTest {

    private static final String PRECONDITION_REQUIRED = "urn:ai-company-os:problem:precondition-required";
    private static final String PRECONDITION_FAILED = "urn:ai-company-os:problem:precondition-failed";
    private static final String INVALID_PRECONDITION = "urn:ai-company-os:problem:invalid-precondition";

    /**
     * An entity-tag no row can currently carry. Versions start at zero and only
     * ever go up, so this is stale by construction rather than by arithmetic on a
     * value the test would have to keep in step.
     */
    private static final String STALE = "\"999\"";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private AgentRepository agentRepository;

    /**
     * State is read back through SQL rather than through the repositories: the
     * question these assertions ask is "did the write land", and the committed row
     * is what answers it without a mapping layer in between.
     */
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearEverything() {
        // Tasks first: they reference projects, and the foreign key says so.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        agentRepository.deleteAll();
    }

    // ------------------------------------------------------------------
    // Where a client gets an ETag -- ADR-009 §5.1
    // ------------------------------------------------------------------

    /**
     * AC-5. The canonical path, and the one that does not exist yet: without
     * {@code GET /api/tasks/{id}} the only way to learn a task's version is to
     * page through every task there is.
     */
    @Test
    void aTaskIsReadableOnItsOwnAndCarriesAnEtag() throws Exception {

        Long taskId = task("Wire the planner");

        mockMvc.perform(get("/api/tasks/{id}", taskId))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.title").value("Wire the planner"));
    }

    /**
     * The new route answers in the registry's own dialect, not with the generic
     * "there is nothing at this path" that an unmapped URL produces.
     */
    @Test
    void anUnknownTaskIsATaskNotFound() throws Exception {

        mockMvc.perform(get("/api/tasks/{id}", 987654L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:task-not-found"));
    }

    @Test
    void aProjectAndAnAgentAlsoCarryAnEtagWhenReadOnTheirOwn() throws Exception {

        mockMvc.perform(get("/api/projects/{id}", project("Company OS")))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG));

        mockMvc.perform(get("/api/agents/{id}", agent("Backend")))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    /**
     * A client that has just created something knows its state and must not have
     * to read it back before it can write again.
     */
    @Test
    void creationCarriesAnEtag() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.ETAG));

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fresh project"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.ETAG));

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fresh agent","role":"Engineer","specialization":"jvm"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.ETAG));
    }

    // ------------------------------------------------------------------
    // P0 -- the header is mandatory. I-1, AC-1
    // ------------------------------------------------------------------

    /**
     * I-1 on all seven mutating routes. The assertion that matters is the second
     * one on each: a refusal that still writes is worse than no refusal at all,
     * because it reports the failure and performs the change.
     */
    @Test
    void everyMutationOfAnExistingResourceRequiresIfMatch() throws Exception {

        Long projectId = project("Company OS");
        Long otherProjectId = project("Second project");
        Long taskId = task("Wire the planner");
        Long agentId = agent("Backend");

        rejectedWithout(put("/api/tasks/{id}/project", taskId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":%d}""".formatted(otherProjectId)));
        assertThat(projectIdOf(taskId)).as("a refused assignment must not have been written").isNull();

        rejectedWithout(put("/api/projects/{id}", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed"}"""));
        assertThat(nameOfProject(projectId)).isEqualTo("Company OS");

        rejectedWithout(post("/api/projects/{id}/archive", projectId));
        assertThat(statusOfProject(projectId)).isEqualTo("ACTIVE");

        rejectedWithout(post("/api/projects/{id}/restore", projectId));
        assertThat(statusOfProject(projectId)).isEqualTo("ACTIVE");

        rejectedWithout(put("/api/agents/{id}", agentId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed","role":"Engineer","specialization":"jvm"}"""));
        assertThat(nameOfAgent(agentId)).isEqualTo("Backend");

        rejectedWithout(post("/api/agents/{id}/deactivate", agentId));
        assertThat(isAgentActive(agentId)).isTrue();

        rejectedWithout(post("/api/agents/{id}/activate", agentId));
        assertThat(isAgentActive(agentId)).isTrue();
    }

    /**
     * AC-9, first half. P0 is a property of the request, and it is checked before
     * the database is touched -- so a caller who supplied no precondition is told
     * about the precondition, not about the existence of a row.
     */
    @Test
    void aMissingPreconditionIsReportedBeforeTheResourceIsLookedUp() throws Exception {

        mockMvc.perform(post("/api/projects/{id}/archive", 987654L))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value(PRECONDITION_REQUIRED));
    }

    /** AC-9, second half. Once the request is inside the contract, 404 wins again. */
    @Test
    void aWellFormedPreconditionOnAnUnknownResourceIsNotFound() throws Exception {

        mockMvc.perform(post("/api/projects/{id}/archive", 987654L)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:project-not-found"));
    }

    // ------------------------------------------------------------------
    // The detection itself. I-2, AC-2
    // ------------------------------------------------------------------

    @Test
    void everyMutationRejectsAStaleEntityTagAndWritesNothing() throws Exception {

        Long projectId = project("Company OS");
        Long otherProjectId = project("Second project");
        Long taskId = task("Wire the planner");
        Long agentId = agent("Backend");

        rejectedWithStale(put("/api/tasks/{id}/project", taskId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":%d}""".formatted(otherProjectId)));
        assertThat(projectIdOf(taskId)).isNull();

        rejectedWithStale(put("/api/projects/{id}", projectId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed"}"""));
        assertThat(nameOfProject(projectId)).isEqualTo("Company OS");

        rejectedWithStale(post("/api/projects/{id}/archive", projectId));
        assertThat(statusOfProject(projectId)).isEqualTo("ACTIVE");

        rejectedWithStale(put("/api/agents/{id}", agentId).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Renamed","role":"Engineer","specialization":"jvm"}"""));
        assertThat(nameOfAgent(agentId)).isEqualTo("Backend");

        rejectedWithStale(post("/api/agents/{id}/deactivate", agentId));
        assertThat(isAgentActive(agentId)).isTrue();
    }

    /**
     * The whole mechanism in one story, and the shape of the lost update it
     * closes: two clients read the same task, the first moves it, the second
     * moves it somewhere else believing it is still where it was.
     *
     * <p>On the baseline the second request wins in silence. Under ADR-009 it is
     * told, and told in a way it can act on: re-read, decide again, retry.
     */
    @Test
    void theSecondWriterIsToldItWasOvertaken() throws Exception {

        Long first = project("First");
        Long second = project("Second");
        Long taskId = task("Wire the planner");

        String bothRead = etagOfTask(taskId);

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, bothRead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(first)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, bothRead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(second)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(PRECONDITION_FAILED));

        assertThat(projectIdOf(taskId))
                .as("the overtaken writer must not have overwritten the change it did not know about")
                .isEqualTo(first);
    }

    // ------------------------------------------------------------------
    // P2 -- before the idempotent short circuit. I-3, AC-3
    // ------------------------------------------------------------------

    /**
     * I-3, and the rule that is easiest to get wrong: {@code assignTo} returns
     * early when the task is already in the requested project, so a precondition
     * placed after it would never run on this path.
     *
     * <p>The request below <em>would</em> be a no-op against the current state.
     * It is still refused, because the intent behind it was formed against a
     * state that no longer exists: this caller does not know the task ever moved,
     * and answering 200 would confirm a belief that happens to be true by
     * coincidence.
     */
    @Test
    void aStalePreconditionIsRefusedEvenWhenTheRequestWouldChangeNothing() throws Exception {

        Long destination = project("Destination");
        Long taskId = task("Wire the planner");

        String beforeAnybodyMovedIt = etagOfTask(taskId);

        // Somebody else moves it there first.
        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, beforeAnybodyMovedIt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(destination)))
                .andExpect(status().isOk());

        // Same destination, stale tag: a no-op against the new state, refused anyway.
        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, beforeAnybodyMovedIt)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(destination)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(PRECONDITION_FAILED));
    }

    /**
     * I-3 again, and this is the one that discriminates.
     *
     * <p>The test above does not, and finding that out is what a mutation is for:
     * moving the comparison below {@code task.assignTo(...)} left the whole suite
     * green. {@code Task.assignTo} returns early from <em>itself</em> when the task
     * is already where it is being sent -- it does not return from the service --
     * so the comparison still ran, and the 412 still arrived. The claim that it
     * would not was wrong, and the sentence that made it has been corrected.
     *
     * <p>What placement really decides is <strong>which refusal a stale caller
     * gets</strong>. Here the request is stale <em>and</em> would be refused on its
     * merits: the destination is archived. With the comparison first, the answer is
     * 412 -- you are out of date. With the comparison after the rules, it is 409 --
     * the destination is archived -- which answers a question this caller did not
     * ask. It does not know the task has moved at all, and telling it about the
     * state of a destination it chose under different information is telling it
     * the wrong thing.
     *
     * <p>Verified by mutation on 2026-09-16: moving the line below
     * {@code assignTo} turns this test, and only this test, red with a 409.
     */
    @Test
    void aStaleCallerIsToldItIsStaleAndNotWhatIsWrongWithTheNewState() throws Exception {

        Long active = project("Active");
        Long archived = project("Archived");
        Long taskId = task("Wire the planner");

        String whatTheStaleCallerRead = etagOfTask(taskId);

        // Somebody else moves the task, so the tag above stops describing the row.
        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, whatTheStaleCallerRead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(active)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/projects/{id}/archive", archived)
                        .header(HttpHeaders.IF_MATCH, etagOfProject(archived)))
                .andExpect(status().isOk());

        // Stale, and also refusable on its merits. Staleness is the answer.
        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, whatTheStaleCallerRead)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(archived)))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(PRECONDITION_FAILED));

        assertThat(projectIdOf(taskId))
                .as("nothing moved: the refusal came before any rule and before any write")
                .isEqualTo(active);
    }

    // ------------------------------------------------------------------
    // P3 -- a version counts its own row. I-5, I-6
    // ------------------------------------------------------------------

    /**
     * I-5, AC-7. Assigning a task writes the task row and nothing else, so the
     * project's entity-tag must not move. If it did, two assignments to the same
     * active project would start refusing each other -- a conflict the domain does
     * not have, and precisely what ADR-006 §4 refused when it rejected
     * {@code OPTIMISTIC_FORCE_INCREMENT}.
     */
    @Test
    void assigningATaskDoesNotChangeTheProjectsEntityTag() throws Exception {

        Long projectId = project("Company OS");
        Long taskId = task("Wire the planner");

        String projectBefore = etagOfProject(projectId);

        mockMvc.perform(put("/api/tasks/{id}/project", taskId)
                        .header(HttpHeaders.IF_MATCH, etagOfTask(taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk());

        assertThat(etagOfProject(projectId))
                .as("the project row was not written, so its version must not have moved")
                .isEqualTo(projectBefore);
    }

    /**
     * I-6. A version counts writes, not requests. A second identical assignment
     * changes no column, so the tag it was made with is still valid afterwards --
     * and a client repeating itself is not punished for a change nobody made.
     */
    @Test
    void aMutationThatChangesNothingDoesNotConsumeTheEntityTag() throws Exception {

        Long projectId = project("Company OS");
        Long taskId = task("Wire the planner");

        String afterFirstMove = mutate(put("/api/tasks/{id}/project", taskId)
                .header(HttpHeaders.IF_MATCH, etagOfTask(taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":%d}""".formatted(projectId)));

        String afterRepeat = mutate(put("/api/tasks/{id}/project", taskId)
                .header(HttpHeaders.IF_MATCH, afterFirstMove)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":%d}""".formatted(projectId)));

        assertThat(afterRepeat)
                .as("the repeat wrote no column, so the version must be where it was")
                .isEqualTo(afterFirstMove);
    }

    // ------------------------------------------------------------------
    // I-7 -- the tag a mutation hands back is usable
    // ------------------------------------------------------------------

    /**
     * I-7, AC-6, and the invariant that catches the flush trap: the version is
     * incremented when the row is written, which happens after a response
     * composed inside the transaction has already been built. A response carrying
     * the pre-write version would hand the client a tag that is stale the moment
     * it arrives -- and the client's next call would be refused for a change it
     * made itself.
     */
    @Test
    void theEntityTagReturnedByAMutationIsTheOneTheNextReadReports() throws Exception {

        Long projectId = project("Company OS");
        Long taskId = task("Wire the planner");
        Long agentId = agent("Backend");

        assertThat(mutate(put("/api/tasks/{id}/project", taskId)
                .header(HttpHeaders.IF_MATCH, etagOfTask(taskId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"projectId":%d}""".formatted(projectId))))
                .as("task: the tag handed back must be the one a read reports")
                .isEqualTo(etagOfTask(taskId));

        assertThat(mutate(put("/api/projects/{id}", projectId)
                .header(HttpHeaders.IF_MATCH, etagOfProject(projectId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Company OS renamed"}""")))
                .as("project update")
                .isEqualTo(etagOfProject(projectId));

        assertThat(mutate(post("/api/projects/{id}/archive", projectId)
                .header(HttpHeaders.IF_MATCH, etagOfProject(projectId))))
                .as("project archive")
                .isEqualTo(etagOfProject(projectId));

        assertThat(mutate(post("/api/agents/{id}/deactivate", agentId)
                .header(HttpHeaders.IF_MATCH, etagOfAgent(agentId))))
                .as("agent deactivate")
                .isEqualTo(etagOfAgent(agentId));
    }

    // ------------------------------------------------------------------
    // The shape of the header itself -- ADR-009 §5.3. I-9, AC-8
    // ------------------------------------------------------------------

    /**
     * I-9. The wildcard means "provided the resource exists", which asserts
     * nothing about the state the caller saw. Accepting it would give every client
     * a documented way out of the protocol, and P0 would become a formality.
     */
    @Test
    void theWildcardPreconditionIsRefused() throws Exception {

        Long projectId = project("Company OS");

        mockMvc.perform(post("/api/projects/{id}/archive", projectId)
                        .header(HttpHeaders.IF_MATCH, "*"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_PRECONDITION));

        assertThat(statusOfProject(projectId)).isEqualTo("ACTIVE");
    }

    /**
     * A weak entity-tag can never satisfy {@code If-Match} -- RFC 9110 requires
     * the strong comparison function there -- so accepting the syntax and then
     * always failing it would be a slower way of saying the same thing, with a
     * status that suggests the caller should re-read.
     */
    @Test
    void aWeakEntityTagIsRefusedAsUnusableRatherThanSilentlyFailed() throws Exception {

        mockMvc.perform(post("/api/projects/{id}/archive", project("Company OS"))
                        .header(HttpHeaders.IF_MATCH, "W/\"0\""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_PRECONDITION));
    }

    @Test
    void anUnreadablePreconditionIsRefused() throws Exception {

        mockMvc.perform(post("/api/projects/{id}/archive", project("Company OS"))
                        .header(HttpHeaders.IF_MATCH, "not-an-entity-tag"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(INVALID_PRECONDITION));
    }

    /**
     * AC-8. A list of explicit entity-tags is an assertion about states the caller
     * has actually seen, unlike the wildcard, so it is honoured: it matches if one
     * of its members is the current version.
     */
    @Test
    void aListOfEntityTagsMatchesWhenOneOfThemIsCurrent() throws Exception {

        Long projectId = project("Company OS");

        mockMvc.perform(post("/api/projects/{id}/archive", projectId)
                        .header(HttpHeaders.IF_MATCH, "\"41\", " + etagOfProject(projectId) + ", \"43\""))
                .andExpect(status().isOk());

        assertThat(statusOfProject(projectId)).isEqualTo("ARCHIVED");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private void rejectedWithout(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.type").value(PRECONDITION_REQUIRED));
    }

    private void rejectedWithStale(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(HttpHeaders.IF_MATCH, STALE))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value(PRECONDITION_FAILED));
    }

    /** Performs a mutation that must succeed, and returns the entity-tag it reports. */
    private String mutate(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).as("a successful mutation must report the new entity-tag").isNotNull();
        return etag;
    }

    private String etagOfTask(Long id) throws Exception {
        return etagOf("/api/tasks/{id}", id);
    }

    private String etagOfProject(Long id) throws Exception {
        return etagOf("/api/projects/{id}", id);
    }

    private String etagOfAgent(Long id) throws Exception {
        return etagOf("/api/agents/{id}", id);
    }

    private String etagOf(String path, Long id) throws Exception {
        MvcResult result = mockMvc.perform(get(path, id)).andExpect(status().isOk()).andReturn();
        String etag = result.getResponse().getHeader(HttpHeaders.ETAG);
        assertThat(etag).as("%s must carry an entity-tag".formatted(path)).isNotNull();
        return etag;
    }

    private Long task(String title) {
        return taskRepository.saveAndFlush(new Task(title, null, "OPEN", "HIGH")).getId();
    }

    private Long project(String name) {
        return projectRepository.saveAndFlush(new Project(name, null)).getId();
    }

    private Long agent(String name) {
        return agentRepository.saveAndFlush(new Agent(name, "Engineer", "jvm")).getId();
    }

    private Long projectIdOf(Long taskId) {
        return jdbc.queryForObject("SELECT project_id FROM tasks WHERE id = ?", Long.class, taskId);
    }

    private String nameOfProject(Long id) {
        return jdbc.queryForObject("SELECT name FROM projects WHERE id = ?", String.class, id);
    }

    private String statusOfProject(Long id) {
        return jdbc.queryForObject("SELECT status FROM projects WHERE id = ?", String.class, id);
    }

    private String nameOfAgent(Long id) {
        return jdbc.queryForObject("SELECT name FROM agents WHERE id = ?", String.class, id);
    }

    private boolean isAgentActive(Long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject("SELECT active FROM agents WHERE id = ?", Boolean.class, id));
    }
}
