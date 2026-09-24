package com.aicompany.backend.api;

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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One error contract, asserted across every family of failure the API can
 * produce: our own domain rules, Bean Validation, the exceptions Spring raises
 * before our code runs, and whatever nobody anticipated.
 *
 * <p>Written before the implementation and seen to fail. On the baseline the API
 * spoke three dialects -- {@code ProblemDetail} under {@code /api/projects},
 * {@code ProblemDetail} for the newer responses under {@code /api/tasks}, and
 * Spring's default everywhere else -- and no test held that against it, because
 * each step had chosen its own scope deliberately and recorded the gap as TD-07.
 *
 * <p>The assertion that matters most is on {@code type}. A title is prose: it
 * should be correctable without breaking a client. A {@code type} is the
 * identifier a client branches on, and it is the thing this contract actually
 * promises (ADR-007 §2).
 */
class ApiErrorContractTest extends AbstractPostgresTest {

    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String TYPE_PREFIX = "urn:ai-company-os:problem:";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @BeforeEach
    void clearEverything() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    // --- validation, the same everywhere ------------------------------------

    /**
     * AC-1. The task API used to answer a validation failure with Spring's
     * default body, {@code {timestamp, status, error, path}}. It now answers the
     * way the project API always did, field list included.
     *
     * <p>This is the breaking change TASK-005 exists to make, and the status code
     * is deliberately unchanged: only the body moves.
     */
    @Test
    void aRejectedTaskPayloadCarriesTheContractAndTheOffendingFields() throws Exception {

        problem(mockMvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")), 400, "validation-failed")
                .andExpect(jsonPath("$.errors").isMap())
                .andExpect(jsonPath("$.errors.title").exists());
    }

    /** AC-2. The project API keeps what it had, and gains a stable type. */
    @Test
    void aRejectedProjectPayloadCarriesTheSameContract() throws Exception {

        problem(mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")), 400, "validation-failed")
                .andExpect(jsonPath("$.errors.name").exists());
    }

    // --- what Spring raises before our code runs (TD-20) --------------------

    @Test
    void malformedJsonIsPartOfTheContract() throws Exception {

        problem(mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":")), 400, "malformed-request");
    }

    @Test
    void aMissingContentTypeIsPartOfTheContract() throws Exception {

        problem(mockMvc.perform(post("/api/projects")
                        .content("{\"name\":\"Company OS\"}")), 415, "unsupported-media-type");
    }

    /**
     * AC-5. Projects are archived, not deleted (ADR-004 §3), so the path exists
     * for other methods and Spring answers 405 without a handler of ours. The
     * status stays exactly that; what changes is that the body is now the same
     * shape as everything else.
     */
    @Test
    void aMethodThatDoesNotExistOnAnExistingPathIsPartOfTheContract() throws Exception {

        Long projectId = activeProject("Company OS");

        problem(mockMvc.perform(patch("/api/projects/" + projectId)), 405, "method-not-allowed");
    }

    // --- TD-27: one route, one dialect --------------------------------------

    /**
     * AC-4. {@code GET /api/projects/999/tasks} and {@code GET /api/projects/abc/tasks}
     * used to answer in two different dialects on the same route, depending on
     * whether the identifier was wrong or unparseable. Same route, same contract.
     */
    @Test
    void aNonNumericIdentifierSpeaksTheSameDialectAsAMissingOne() throws Exception {

        problem(mockMvc.perform(get("/api/projects/999/tasks")), 404, "project-not-found");
        problem(mockMvc.perform(get("/api/projects/abc/tasks")), 400, "invalid-parameter");

        problem(mockMvc.perform(put("/api/tasks/abc/project")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":1}")), 400, "invalid-parameter");
    }

    @Test
    void anUnknownEnumValueOnAQueryParameterIsPartOfTheContract() throws Exception {

        problem(mockMvc.perform(get("/api/projects?status=SOMETHING")), 400, "invalid-parameter");
    }

    // --- AC-7: every domain rule has its own identifier ---------------------

    @Test
    void everyDomainRefusalCarriesItsOwnType() throws Exception {

        Long active = activeProject("Company OS");
        Long archived = archivedProject("Retired");
        Long taskId = taskRepository.saveAndFlush(new Task("t", null, TaskStatus.OPEN, TaskPriority.HIGH)).getId();

        problem(mockMvc.perform(get("/api/projects/424242")), 404, "project-not-found");

        problem(mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Company OS\"}")), 409, "project-name-conflict");

        problem(mockMvc.perform(post("/api/projects/" + archived + "/archive").header(HttpHeaders.IF_MATCH, projectEtag(archived))),
                409, "illegal-project-state-transition");

        problem(mockMvc.perform(put("/api/projects/" + archived).header(HttpHeaders.IF_MATCH, projectEtag(archived))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}")), 409, "archived-project-is-immutable");

        problem(mockMvc.perform(put("/api/tasks/424242/project").header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":%d}".formatted(active))), 404, "task-not-found");

        problem(mockMvc.perform(put("/api/tasks/" + taskId + "/project").header(HttpHeaders.IF_MATCH, taskEtag(taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":%d}".formatted(archived))),
                409, "archived-project-cannot-receive-tasks");

        mockMvc.perform(put("/api/tasks/" + taskId + "/project").header(HttpHeaders.IF_MATCH, taskEtag(taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":%d}".formatted(active)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/projects/" + active + "/archive").header(HttpHeaders.IF_MATCH, projectEtag(active))).andExpect(status().isOk());

        Long elsewhere = activeProject("Planner");
        problem(mockMvc.perform(put("/api/tasks/" + taskId + "/project").header(HttpHeaders.IF_MATCH, taskEtag(taskId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"projectId\":%d}".formatted(elsewhere))),
                409, "archived-project-task-is-immutable");
    }

    /**
     * AC-6. The oldest endpoint in the codebase is inside the contract too --
     * validation, a missing resource, and the method the registry refuses.
     *
     * <p>Until TASK-007 this asserted 405 on POST, because the agent API had no
     * POST at all. The registry gave it one; what it still refuses is DELETE, for
     * the reason ADR-004 §3 gave for projects.
     */
    @Test
    void theAgentApiIsInsideTheContract() throws Exception {

        problem(mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")), 400, "validation-failed")
                .andExpect(jsonPath("$.errors.name").exists());

        problem(mockMvc.perform(get("/api/agents/424242")), 404, "agent-not-found");

        problem(mockMvc.perform(delete("/api/agents/1")), 405, "method-not-allowed");
    }

    // --- preconditions ------------------------------------------------------

    /**
     * The entity-tag a caller would have read before writing.
     *
     * <p>Every mutation in this file carries one, because since ADR-009 there is no
     * other way in: a request without {@code If-Match} is refused with 428 before
     * anything is looked up. Reading it here, at the point of the call, is what a
     * client does -- and it means these tests assert the domain rules against a
     * <em>fresh</em> tag, so a 409 that turned into a 412 would show up as a
     * failure rather than pass unnoticed.
     */
    private String etagOf(String path) throws Exception {

        String etag = mockMvc.perform(get(path))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(etag).as("%s must carry an entity-tag".formatted(path)).isNotNull();
        return etag;
    }

    private String projectEtag(Long id) throws Exception {
        return etagOf("/api/projects/" + id);
    }

    private String taskEtag(Long id) throws Exception {
        return etagOf("/api/tasks/" + id);
    }

    // --- helpers -----------------------------------------------------------

    private ResultActions problem(ResultActions response, int status, String slug) throws Exception {
        return response
                .andExpect(status().is(status))
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value(TYPE_PREFIX + slug))
                .andExpect(jsonPath("$.status").value(status))
                .andExpect(jsonPath("$.title").isString())
                .andExpect(jsonPath("$.detail").isString());
    }

    private Long activeProject(String name) {
        return projectRepository.saveAndFlush(new Project(name, null)).getId();
    }

    private Long archivedProject(String name) {
        Project project = new Project(name, null);
        project.archive();
        return projectRepository.saveAndFlush(project).getId();
    }
}
