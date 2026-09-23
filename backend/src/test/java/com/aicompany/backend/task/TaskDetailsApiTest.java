package com.aicompany.backend.task;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TASK-016: a task's details can be corrected, its priority means something, and
 * the listing can be asked for one state.
 *
 * <p>The priority half follows TASK-010 step by step, because it is the same
 * defect on the neighbouring field (TD-36): a census first
 * ({@code tasks/TASK-016/CENSUS.md}), then three guards that must agree.
 */
class TaskDetailsApiTest extends AbstractPostgresTest {

    private static final String VALIDATION = "urn:ai-company-os:problem:validation-failed";
    private static final String FROZEN = "urn:ai-company-os:problem:archived-project-task-is-immutable";

    /** Written out, not read from the enum: see TaskStatusPinTest for why. */
    private static final Set<String> PRIORITY_VOCABULARY = Set.of("LOW", "MEDIUM", "HIGH");

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
    // PUT /api/tasks/{id}: the details, and only the details
    // ------------------------------------------------------------------

    @Test
    void theDetailsOfATaskCanBeReplaced() throws Exception {

        Long taskId = task("Draft", TaskStatus.OPEN);

        mockMvc.perform(update(taskId, etagOf(taskId), """
                        {"title":"Final","description":"now with words","priority":"MEDIUM"}"""))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(jsonPath("$.title").value("Final"))
                .andExpect(jsonPath("$.description").value("now with words"))
                .andExpect(jsonPath("$.priority").value("MEDIUM"))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    /**
     * The status is not a detail. A PUT that carries one must not move the task:
     * the lifecycle has its own edges (ADR-014), and this route is not one of them.
     */
    @Test
    void aStatusInTheBodyDoesNotMoveTheTask() throws Exception {

        Long taskId = task("Draft", TaskStatus.OPEN);

        mockMvc.perform(update(taskId, etagOf(taskId), """
                        {"title":"Draft","priority":"LOW","status":"DONE"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));

        assertThat(jdbc.queryForObject("SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("OPEN");
    }

    @Test
    void theAssociationsAreNotDetailsEither() throws Exception {

        Long project = projectRepository.saveAndFlush(new Project("Home", null)).getId();
        Long taskId = task("Draft", TaskStatus.OPEN);
        jdbc.update("UPDATE tasks SET project_id = ? WHERE id = ?", project, taskId);

        mockMvc.perform(update(taskId, etagOf(taskId), """
                        {"title":"Draft","priority":"LOW","projectId":null}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project));
    }

    @Test
    void theDetailsAreValidatedLikeACreation() throws Exception {

        Long taskId = task("Draft", TaskStatus.OPEN);

        mockMvc.perform(update(taskId, etagOf(taskId), """
                        {"title":"","priority":"URGENT"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION))
                .andExpect(jsonPath("$.errors.title").exists())
                .andExpect(jsonPath("$.errors.priority").value(containsString("LOW, MEDIUM, HIGH")));

        assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("Draft");
    }

    @Test
    void aTaskInAnArchivedProjectCannotBeEdited() throws Exception {

        Long project = projectRepository.saveAndFlush(new Project("Frozen", null)).getId();
        Long taskId = task("Draft", TaskStatus.OPEN);
        jdbc.update("UPDATE tasks SET project_id = ? WHERE id = ?", project, taskId);
        jdbc.update("UPDATE projects SET status = 'ARCHIVED' WHERE id = ?", project);

        mockMvc.perform(update(taskId, etagOf(taskId), """
                        {"title":"Changed","priority":"LOW"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(FROZEN));
    }

    @Test
    void anEditRequiresTheTagAndRefusesAStaleOne() throws Exception {

        Long taskId = task("Draft", TaskStatus.OPEN);
        String stale = etagOf(taskId);

        mockMvc.perform(put("/api/tasks/{id}", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"priority\":\"LOW\"}"))
                .andExpect(status().isPreconditionRequired());

        mockMvc.perform(update(taskId, stale, "{\"title\":\"first\",\"priority\":\"LOW\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(update(taskId, stale, "{\"title\":\"second\",\"priority\":\"LOW\"}"))
                .andExpect(status().isPreconditionFailed());

        assertThat(jdbc.queryForObject("SELECT title FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("first");
    }

    @Test
    void finishedWorkCanStillHaveItsDescriptionCorrected() throws Exception {

        Long taskId = task("Shipped", TaskStatus.DONE);

        mockMvc.perform(update(taskId, etagOf(taskId), """
                        {"title":"Shipped","description":"what it actually did","priority":"HIGH"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    // ------------------------------------------------------------------
    // TD-36: the priority vocabulary, three guards that agree
    // ------------------------------------------------------------------

    @Test
    void creationRefusesAPriorityOutsideTheVocabularyAndNamesTheField() throws Exception {

        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"status\":\"OPEN\",\"priority\":\"urgent\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(VALIDATION))
                .andExpect(jsonPath("$.errors.priority").value(containsString("LOW, MEDIUM, HIGH")));

        assertThat(taskRepository.count()).isZero();
    }

    @Test
    void everyPriorityOfTheVocabularyIsAcceptedAndComesBackUnchanged() throws Exception {

        for (String priority : PRIORITY_VOCABULARY) {
            mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"t\",\"status\":\"OPEN\",\"priority\":\"" + priority + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.priority").value(priority));
        }
    }

    /** Case-sensitive, for the reason ADR-011 §5 gave for status: a program sends it. */
    @Test
    void thePriorityIsCaseSensitive() throws Exception {

        mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"t\",\"status\":\"OPEN\",\"priority\":\"high\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theEnumIsExactlyTheDecidedVocabulary() {

        assertThat(Arrays.stream(TaskPriority.values()).map(Enum::name).collect(Collectors.toSet()))
                .isEqualTo(PRIORITY_VOCABULARY);
    }

    @Test
    void theCheckConstraintDeclaresTheDecidedVocabulary() {

        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                WHERE t.relname = 'tasks' AND c.conname = 'tasks_priority_check'""", String.class);

        Matcher quoted = Pattern.compile("'([^']*)'").matcher(definition);
        Set<String> declared = new java.util.HashSet<>();
        while (quoted.find()) {
            declared.add(quoted.group(1));
        }
        assertThat(declared).isEqualTo(PRIORITY_VOCABULARY);
    }

    @Test
    void theDatabaseRefusesAPriorityOutsideTheVocabularyOnBothPaths() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tasks (title, status, priority) VALUES ('t', 'OPEN', 'URGENT')"))
                .isInstanceOf(DataIntegrityViolationException.class);

        Long taskId = task("Draft", TaskStatus.OPEN);
        assertThatThrownBy(() -> jdbc.update("UPDATE tasks SET priority = 'low' WHERE id = ?", taskId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // GET /api/tasks?status=
    // ------------------------------------------------------------------

    @Test
    void theListingCanBeAskedForOneState() throws Exception {

        task("open one", TaskStatus.OPEN);
        task("open two", TaskStatus.OPEN);
        task("working", TaskStatus.IN_PROGRESS);
        task("finished", TaskStatus.DONE);

        mockMvc.perform(get("/api/tasks").param("status", "OPEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].title").value("open one"))
                .andExpect(jsonPath("$[1].title").value("open two"));

        mockMvc.perform(get("/api/tasks").param("status", "DONE"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("finished"));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(jsonPath("$", hasSize(4)));
    }

    @Test
    void anUnknownStateInTheFilterIsAnInvalidParameterNotAnEmptyList() throws Exception {

        mockMvc.perform(get("/api/tasks").param("status", "banana"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:invalid-parameter"));
    }

    // --- helpers -----------------------------------------------------------

    private MockHttpServletRequestBuilder update(Long taskId, String ifMatch, String body) {
        return put("/api/tasks/{id}", taskId)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String etagOf(Long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/{id}", taskId)).andExpect(status().isOk()).andReturn()
                .getResponse().getHeader(HttpHeaders.ETAG);
    }

    private Long task(String title, TaskStatus status) {
        return taskRepository.saveAndFlush(new Task(title, null, status, TaskPriority.HIGH)).getId();
    }
}
