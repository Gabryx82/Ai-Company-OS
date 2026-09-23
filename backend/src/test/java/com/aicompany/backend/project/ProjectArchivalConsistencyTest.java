package com.aicompany.backend.project;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.repository.TaskRepository;
import com.aicompany.backend.task.service.TaskService;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Archival consistency is <em>derived</em>: archiving a project changes the rules
 * that apply to its tasks, and changes nothing about their rows (ADR-006 §1).
 *
 * <p>This is the test that makes that claim falsifiable. Asserting only that the
 * values look the same afterwards would not: a cascade that wrote every task and
 * wrote it back identically would pass. So the assertion is on the writes
 * themselves, counted by Hibernate, and it is zero.
 *
 * <p>The consequence worth having is {@code restore}: with nothing written there
 * is nothing to remember and nothing to undo, so the inverse is exact by
 * construction rather than by bookkeeping.
 */
class ProjectArchivalConsistencyTest extends AbstractPostgresTest {

    private static final String TASK_ENTITY = "com.aicompany.backend.task.model.Task";

    @Autowired
    private ProjectService projectService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager entityManager;

    private Statistics statistics;

    @BeforeEach
    void startFromAnEmptyRegistry() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
    }

    /**
     * AC-1, invariant I-1. Archiving a project with tasks in it issues zero
     * updates against the task entity.
     */
    @Test
    void archivingAProjectWritesNoTaskRow() {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        Long first = taskIn(projectId, "Wire the planner");
        Long second = taskIn(projectId, "Draw the graph");

        List<Map<String, Object>> before = taskRows();
        long writesBefore = taskUpdateCount();

        projectService.archive(projectId, projectPrecondition(projectId));

        assertThat(taskUpdateCount() - writesBefore)
                .as("""
                    I-1: archival consistency is derived, so archiving writes one row -- its \
                    own. A single update against a task here means the cascade was \
                    materialised after all, and with it comes the bookkeeping that makes \
                    restore stop being an exact inverse.""")
                .isZero();

        assertThat(taskRows()).isEqualTo(before);
        assertThat(taskRows()).hasSize(2);

        // Positive control. A counter that never moves proves nothing about the
        // archive: it could just as well mean the instrument is dead. Restoring
        // and then writing a task on purpose has to move it.
        projectService.restore(projectId, projectPrecondition(projectId));
        long writesBeforeARealOne = taskUpdateCount();
        taskService.assignToProject(second, projectRepository
                .saveAndFlush(new Project("Planner", null)).getId(), taskPrecondition(second));

        assertThat(taskUpdateCount() - writesBeforeARealOne)
                .as("the statistic does move when a task really is written")
                .isPositive();

        assertThat(first).isNotEqualTo(second);
    }

    /**
     * The property the freeze guard's correctness rests on, pinned so that it
     * cannot quietly stop being true.
     *
     * <p>{@code TaskService.assignToProject} takes the task row exclusively (L0),
     * reads the id of its project, and only then locks that project shared (L2).
     * That ordering is sound only if reading the id does <em>not</em> load the
     * project. If it did, the association would be materialised before the lock,
     * and {@code Task.assignTo} would later decide the frozen rule on state read
     * without one: a project archived and committed in between would still look
     * active, because Hibernate does not overwrite an entity already loaded into
     * the session.
     *
     * <p>A Hibernate proxy answers its identifier getter without initialising, so
     * the first real load of that project is the {@code FOR SHARE} query and the
     * proxy then resolves to fresh, locked state. This test is what stops a later
     * {@code join fetch} -- or bytecode enhancement -- from taking that away
     * silently.
     *
     * <p>Its own project names, because it runs inside one transaction: Hibernate
     * orders a flush inserts-first, deletes-last, so a fresh row reusing a name
     * the cleanup is about to delete collides with the unique index.
     */
    @Test
    @Transactional
    void readingTheProjectIdDoesNotLoadTheProject() {

        Long projectId = projectRepository
                .saveAndFlush(new Project("Lazy Path Probe", null)).getId();
        Long taskId = taskIn(projectId, "Wire the planner");

        // Flush first: clear() on its own would discard the unflushed assignment
        // and hand back a task with no project at all, which every assertion
        // below would then pass for the wrong reason.
        entityManager.flush();
        entityManager.clear();

        Task task = taskRepository.findByIdForUpdate(taskId).orElseThrow();

        assertThat(task.getProject())
                .as("fixture: the task must really have a project to be lazy about")
                .isNotNull();

        assertThat(Hibernate.isInitialized(task.getProject()))
                .as("the association must still be a proxy right after the task row is locked")
                .isFalse();

        assertThat(task.getProjectId()).isEqualTo(projectId);

        assertThat(Hibernate.isInitialized(task.getProject()))
                .as("""
                    reading the identifier must not load the project. The L0 lock covers the                     task row; the project is meant to be materialised by the FOR SHARE query                     that locks it, and by nothing earlier. Loading it here would let the                     frozen rule be decided on state that was read without a lock.""")
                .isFalse();
    }

    /**
     * AC-2, invariant I-2. A full archive/restore cycle leaves the task rows
     * byte-for-byte where they were, without any state having been remembered.
     */
    @Test
    void restoreIsTheExactInverseOfArchive() {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        taskIn(projectId, "Wire the planner");
        taskIn(projectId, "Draw the graph");

        List<Map<String, Object>> beforeArchive = taskRows();
        long writesBefore = taskUpdateCount();

        projectService.archive(projectId, projectPrecondition(projectId));
        List<Map<String, Object>> whileArchived = taskRows();

        projectService.restore(projectId, projectPrecondition(projectId));
        List<Map<String, Object>> afterRestore = taskRows();

        assertThat(whileArchived).isEqualTo(beforeArchive);
        assertThat(afterRestore).isEqualTo(beforeArchive);

        assertThat(taskUpdateCount() - writesBefore)
                .as("I-2: neither direction of the cycle touches a task row")
                .isZero();
    }

    /**
     * AC-3, invariant I-3, read side. Archiving takes a project out of the working
     * registry; it does not make its history unreadable. ADR-004 §8 restricts
     * writes, and ADR-005 §6 already settled that a project of which the tasks
     * could no longer be read would be archival dressed up as deletion.
     */
    @Test
    void theTasksOfAnArchivedProjectStayReadable() throws Exception {

        Long projectId = projectRepository.saveAndFlush(new Project("Company OS", null)).getId();
        taskIn(projectId, "Wire the planner");

        projectService.archive(projectId, projectPrecondition(projectId));

        mockMvc.perform(get("/api/projects/" + projectId + "/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Wire the planner"))
                .andExpect(jsonPath("$[0].projectId").value(projectId));

        mockMvc.perform(get("/api/tasks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].projectId").value(projectId));

        mockMvc.perform(post("/api/projects/" + projectId + "/archive").header(HttpHeaders.IF_MATCH, projectEtag(projectId)))
                .andExpect(status().isConflict());
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

    // --- helpers -----------------------------------------------------------

    /**
     * The precondition a caller would send: read the resource, take its
     * entity-tag, then write. Rule P0 leaves no other way in, and these tests go
     * through the same door a client does rather than around it.
     */
    private Precondition projectPrecondition(Long id) {
        return Preconditions.at(projectRepository.findById(id).orElseThrow().getVersion());
    }

    private Precondition taskPrecondition(Long id) {
        return Preconditions.at(taskRepository.findById(id).orElseThrow().getVersion());
    }

    private Long taskIn(Long projectId, String title) {
        Long taskId = taskRepository.saveAndFlush(new Task(title, null, TaskStatus.OPEN, TaskPriority.HIGH)).getId();
        taskService.assignToProject(taskId, projectId, taskPrecondition(taskId));
        return taskId;
    }

    /** Straight through JDBC: the rows, not what a persistence context remembers. */
    private List<Map<String, Object>> taskRows() {
        return jdbc.queryForList(
                "SELECT id, title, description, status, priority, project_id FROM tasks ORDER BY id");
    }

    private long taskUpdateCount() {
        return statistics.getEntityStatistics(TASK_ENTITY).getUpdateCount();
    }
}
