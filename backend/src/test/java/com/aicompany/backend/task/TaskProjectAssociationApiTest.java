package com.aicompany.backend.task;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the Task to Project association: creating a task inside a
 * project, attaching an existing one, reading the tasks of a project, and the
 * refusals.
 */
class TaskProjectAssociationApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @BeforeEach
    void clearEverything() {
        // Tasks first: they reference projects, and the foreign key says so.
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // --- create with a project ---------------------------------------------

    @Test
    void taskCanBeCreatedInsideAProject() throws Exception {

        Long projectId = activeProject("Company OS");

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH","projectId":%d}"""
                                .formatted(projectId)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.projectId").value(projectId));
    }

    @Test
    void taskCreatedWithoutAProjectIsUnassigned() throws Exception {

        // The pre-relation contract still works unchanged.
        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectId").doesNotExist());

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").doesNotExist());
    }

    @Test
    void creatingATaskInAnUnknownProjectIsRejectedAndNothingIsPersisted() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH","projectId":987654}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Project not found"));

        // The task must not exist half-placed: the assignment happens before the
        // insert, in the same transaction.
        assertThat(taskRepository.count()).isZero();
    }

    @Test
    void creatingATaskInAnArchivedProjectIsRejectedAndNothingIsPersisted() throws Exception {

        Long projectId = archivedProject("Retired");

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH","projectId":%d}"""
                                .formatted(projectId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Archived project cannot receive tasks"));

        assertThat(taskRepository.count()).isZero();
    }

    @Test
    void negativeProjectIdIsRejectedByValidation() throws Exception {

        mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"t","status":"OPEN","priority":"HIGH","projectId":-1}"""))
                .andExpect(status().isBadRequest());

        assertThat(taskRepository.count()).isZero();
    }

    // --- assigning an existing task ----------------------------------------

    @Test
    void existingUnassignedTaskCanBeAttachedToAProject() throws Exception {

        Long taskId = unassignedTask("created before the relation existed");
        Long projectId = activeProject("Company OS");

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(taskId))
                .andExpect(jsonPath("$.projectId").value(projectId))
                // The rest of the task is not touched by an assignment.
                .andExpect(jsonPath("$.title").value("created before the relation existed"));
    }

    @Test
    void taskCanBeMovedToADifferentProject() throws Exception {

        Long taskId = unassignedTask("t");
        Long first = activeProject("First");
        Long second = activeProject("Second");

        assignTo(taskId, first);

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(second)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(second));

        // Moved, not copied.
        mockMvc.perform(get("/api/projects/" + first + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void assigningATaskToTheProjectItIsAlreadyInIsIdempotent() throws Exception {

        Long taskId = unassignedTask("t");
        Long projectId = activeProject("Company OS");

        assignTo(taskId, projectId);

        // Unlike a repeated archive, this is not a caller mistake to expose: the
        // requested end state is already the current one. ADR-005 section 5.
        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId));
    }

    @Test
    void assigningAnUnknownTaskIsNotFound() throws Exception {

        Long projectId = activeProject("Company OS");

        mockMvc.perform(put("/api/tasks/987654/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isNotFound())
                // Both identifiers in this request can fail to resolve, so the
                // status alone is ambiguous and the title is what disambiguates.
                .andExpect(jsonPath("$.title").value("Task not found"));
    }

    @Test
    void assigningToAnUnknownProjectIsNotFound() throws Exception {

        Long taskId = unassignedTask("t");

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":987654}"""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Project not found"));
    }

    @Test
    void assigningToAnArchivedProjectIsAConflictAndLeavesTheTaskAlone() throws Exception {

        Long taskId = unassignedTask("t");
        Long archived = archivedProject("Retired");

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(archived)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Archived project cannot receive tasks"));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].projectId").doesNotExist());
    }

    @Test
    void restoringTheProjectMakesTheSameAssignmentSucceed() throws Exception {

        Long taskId = unassignedTask("t");
        Long projectId = archivedProject("Retired");

        // 409 rather than 403 because the caller can lift the refusal itself.
        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/projects/" + projectId + "/restore"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk());
    }

    @Test
    void assignmentRequestWithoutAProjectIdIsRejected() throws Exception {

        Long taskId = unassignedTask("t");

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // --- reading the tasks of a project ------------------------------------

    @Test
    void tasksOfAProjectAreListedOldestFirstAndScopedToIt() throws Exception {

        Long projectId = activeProject("Company OS");
        Long otherId = activeProject("Something else");

        Long first = unassignedTask("first");
        Long second = unassignedTask("second");
        Long elsewhere = unassignedTask("elsewhere");
        unassignedTask("still unassigned");

        assignTo(first, projectId);
        assignTo(second, projectId);
        assignTo(elsewhere, otherId);

        mockMvc.perform(get("/api/projects/" + projectId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].title").value("first"))
                .andExpect(jsonPath("$[1].title").value("second"))
                .andExpect(jsonPath("$[0].projectId").value(projectId));
    }

    @Test
    void aProjectWithNoTasksAnswersWithAnEmptyList() throws Exception {

        Long projectId = activeProject("Empty");

        mockMvc.perform(get("/api/projects/" + projectId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void tasksOfAnUnknownProjectAreNotFoundRatherThanEmpty() throws Exception {

        // "No such project" and "that project has no tasks" are different
        // answers, and a client acts differently on them.
        mockMvc.perform(get("/api/projects/987654/tasks"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Project not found"));
    }

    @Test
    void tasksOfAnArchivedProjectAreStillReadable() throws Exception {

        Long projectId = activeProject("Company OS");
        Long taskId = unassignedTask("t");
        assignTo(taskId, projectId);

        mockMvc.perform(post("/api/projects/" + projectId + "/archive"))
                .andExpect(status().isOk());

        // Archiving takes a project out of the working registry. It does not make
        // its history unreadable, and it does not touch the tasks themselves:
        // cascading archive is explicitly not part of this task (ADR-005 §4).
        mockMvc.perform(get("/api/projects/" + projectId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].projectId").value(projectId));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].projectId").value(projectId));
    }

    // --- what the relation must not change ---------------------------------

    @Test
    void archivingAProjectLeavesItsTasksExactlyAsTheyWere() throws Exception {

        Long projectId = activeProject("Company OS");
        Long taskId = unassignedTask("t");
        assignTo(taskId, projectId);

        mockMvc.perform(post("/api/projects/" + projectId + "/archive"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$[0].status").value("OPEN"))
                .andExpect(jsonPath("$[0].projectId").value(projectId));

        assertThat(taskRepository.count()).isEqualTo(1);
    }

    // The guard that TASK-003 did not widen the pre-existing 400 contract is
    // TaskExceptionHandlerScopeTest, not a request here. Asserting the shape of
    // that body through MockMvc cannot work: Spring's default handling calls
    // sendError without dispatching to /error, so the recorded body is empty and
    // every doesNotExist() on it passes whatever the real contract says.

    @Test
    void theProjectApiKeepsNoOpinionAboutTasks() throws Exception {

        Long projectId = activeProject("Company OS");
        Long taskId = unassignedTask("t");
        assignTo(taskId, projectId);

        // The relation is unidirectional: a project does not carry its tasks, and
        // putting them in its payload would be a contract change nobody asked for.
        mockMvc.perform(get("/api/projects/" + projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tasks").doesNotExist());
    }

    // --- the frozen rule: ADR-006 section 2 ---------------------------------

    /**
     * AC-4. A task inside an archived project does not move, and restoring that
     * project is what lets it move again -- the same three-request flow ADR-004
     * section 8 already accepted for editing an archived project, applied to one
     * more resource.
     */
    @Test
    void aTaskInsideAnArchivedProjectCannotBeMovedOut() throws Exception {

        Long source = activeProject("Company OS");
        Long destination = activeProject("Planner");
        Long taskId = unassignedTask("Wire the planner");
        assignTo(taskId, source);

        mockMvc.perform(post("/api/projects/" + source + "/archive")).andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(destination)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Task in an archived project cannot be modified"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(String.valueOf(source))));

        assertThat(projectIdOf(taskId)).isEqualTo(source);

        mockMvc.perform(post("/api/projects/" + source + "/restore")).andExpect(status().isOk());
        assignTo(taskId, destination);

        assertThat(projectIdOf(taskId)).isEqualTo(destination);
    }

    /**
     * AC-5. Both projects archived: the refusal names the <em>source</em>, because
     * the task is frozen before anybody looks at where it was going. Deterministic,
     * not incidental -- ADR-006 section 7.
     */
    @Test
    void whenBothProjectsAreArchivedTheSourceIsWhatIsReported() throws Exception {

        Long source = activeProject("Company OS");
        Long taskId = unassignedTask("Wire the planner");
        assignTo(taskId, source);
        mockMvc.perform(post("/api/projects/" + source + "/archive")).andExpect(status().isOk());

        Long destination = archivedProject("Planner");

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(destination)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Task in an archived project cannot be modified"));
    }

    /**
     * AC-6. Confirming the project a task is already in changes nothing, so there
     * is nothing to refuse -- even when that project is archived. ADR-005 section 5
     * settled that a PUT declaring an already-true state is right; freezing is
     * about mutations.
     */
    @Test
    void reassigningATaskToTheArchivedProjectItIsAlreadyInIsANoOp() throws Exception {

        Long projectId = activeProject("Company OS");
        Long taskId = unassignedTask("Wire the planner");
        assignTo(taskId, projectId);
        mockMvc.perform(post("/api/projects/" + projectId + "/archive")).andExpect(status().isOk());

        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(projectId));

        assertThat(projectIdOf(taskId)).isEqualTo(projectId);
    }

    /**
     * AC-8 / I-10. The public shape is exactly the one TASK-003 published. The
     * discoverability of the frozen state was deliberately left out of TASK-004
     * (ADR-006 section 3), and this is what says so in a way that fails if somebody
     * adds a field without deciding to.
     */
    @Test
    void theTaskResponseShapeIsUnchanged() throws Exception {

        Long projectId = activeProject("Company OS");
        Long taskId = unassignedTask("Wire the planner");
        assignTo(taskId, projectId);

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].*", org.hamcrest.Matchers.hasSize(6)))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].title").exists())
                .andExpect(jsonPath("$[0].status").exists())
                .andExpect(jsonPath("$[0].priority").exists())
                .andExpect(jsonPath("$[0].projectId").value(projectId))
                .andExpect(jsonPath("$[0].projectStatus").doesNotExist());
    }

    // --- helpers -----------------------------------------------------------

    private Long projectIdOf(Long taskId) {
        return taskRepository.findAllWithProject().stream()
                .filter(task -> task.getId().equals(taskId))
                .findFirst()
                .orElseThrow()
                .getProjectId();
    }


    private Long activeProject(String name) {
        return projectRepository.saveAndFlush(new Project(name, null)).getId();
    }

    private Long archivedProject(String name) {
        Project project = new Project(name, null);
        project.archive();
        return projectRepository.saveAndFlush(project).getId();
    }

    private Long unassignedTask(String title) {
        return taskRepository.saveAndFlush(new Task(title, null, "OPEN", "HIGH")).getId();
    }

    private void assignTo(Long taskId, Long projectId) throws Exception {
        mockMvc.perform(put("/api/tasks/" + taskId + "/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId":%d}""".formatted(projectId)))
                .andExpect(status().isOk());
    }
}
