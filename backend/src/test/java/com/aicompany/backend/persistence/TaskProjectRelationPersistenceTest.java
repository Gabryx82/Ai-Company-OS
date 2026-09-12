package com.aicompany.backend.persistence;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.repository.TaskRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Task to Project relation against real PostgreSQL: the round trip, the
 * invariants the database enforces on its own, and the rule the entity enforces.
 */
class TaskProjectRelationPersistenceTest extends AbstractPostgresTest {

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @PersistenceContext
    private EntityManager entityManager;

    @BeforeEach
    void clearEverything() {
        taskRepository.deleteAll();
        projectRepository.deleteAll();
    }

    @Test
    @Transactional
    void associationSurvivesAWriteAndReadCycle() {

        Project project = projectRepository.saveAndFlush(new Project("Company OS", null));

        Task task = new Task("Persisted task", null, "OPEN", "HIGH");
        task.assignTo(project);
        Long taskId = taskRepository.saveAndFlush(task).getId();

        // Without this the test asserts against memory. JPA guarantees one
        // instance per identity inside a persistence context, so findById would
        // hand back the very object just written -- no SELECT is issued, and the
        // test would stay green even if the column were never read back.
        entityManager.clear();

        Task reloaded = taskRepository.findById(taskId).orElseThrow();

        // Proof that the clear above did its job. If somebody removes it, this
        // fails first and says why, instead of the test quietly going hollow.
        assertThat(reloaded).isNotSameAs(task);

        assertThat(reloaded.getTitle()).isEqualTo("Persisted task");
        assertThat(reloaded.getProjectId()).isEqualTo(project.getId());

        // Reading through the association initialises the lazy reference against
        // a real row: the only place the LAZY mapping is exercised, since every
        // production read path resolves the project with a join fetch.
        assertThat(reloaded.getProject().getName()).isEqualTo("Company OS");
    }

    @Test
    void aTaskWithoutAProjectIsAValidRow() {

        // This is the state every task created before V3 is in, and it has to be
        // expressible rather than tolerated: ADR-005 §1.
        Task saved = taskRepository.saveAndFlush(new Task("Unassigned", null, "OPEN", "LOW"));

        assertThat(saved.getProjectId()).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM tasks WHERE id = ? AND project_id IS NULL",
                Integer.class, saved.getId()))
                .isEqualTo(1);
    }

    @Test
    void databaseRejectsATaskPointingAtAProjectThatDoesNotExist() {

        // Written straight through JDBC, past the service: the foreign key is the
        // invariant, the service check is only what makes the error readable.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tasks (title, status, priority, project_id) VALUES ('t', 'OPEN', 'LOW', 987654)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRefusesToDeleteAProjectThatStillHasTasks() {

        Project project = projectRepository.saveAndFlush(new Project("Company OS", null));

        Task task = new Task("t", null, "OPEN", "HIGH");
        task.assignTo(project);
        taskRepository.saveAndFlush(task);

        // No ON DELETE clause on purpose. A cascade would let a delete nobody
        // designed destroy the tasks of a project, and the Company OS archives
        // projects instead of deleting them (ADR-004 §3).
        assertThatThrownBy(() -> jdbc.update("DELETE FROM projects WHERE id = ?", project.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(projectRepository.count()).isEqualTo(1);
    }

    @Test
    void anArchivedProjectRefusesToReceiveATask() {

        Project project = new Project("Retired", null);
        project.archive();
        projectRepository.saveAndFlush(project);

        Task task = new Task("t", null, "OPEN", "HIGH");

        // The rule is on the entity, so it holds for every entry point and not
        // only for the HTTP one.
        assertThatThrownBy(() -> task.assignTo(project))
                .isInstanceOf(ArchivedProjectCannotReceiveTasksException.class);

        assertThat(task.getProject()).isNull();
    }

    @Test
    void archivingAProjectDoesNotDetachOrChangeItsTasks() {

        Project project = projectRepository.saveAndFlush(new Project("Company OS", null));

        Task task = new Task("t", null, "OPEN", "HIGH");
        task.assignTo(project);
        Long taskId = taskRepository.saveAndFlush(task).getId();

        project.archive();
        projectRepository.saveAndFlush(project);

        // TASK-003 establishes the relation and nothing more: no cascading
        // archive, no detach, no delete. That decision is deliberately left open
        // (ADR-005 §4), and this test is what will fail first if it is taken by
        // accident.
        assertThat(jdbc.queryForObject(
                "SELECT project_id FROM tasks WHERE id = ?", Long.class, taskId))
                .isEqualTo(project.getId());

        assertThat(jdbc.queryForObject(
                "SELECT status FROM tasks WHERE id = ?", String.class, taskId))
                .isEqualTo("OPEN");
    }

    @Test
    void aProjectCanHoldManyTasksAndTheyAreReadBackOldestFirst() {

        Project project = projectRepository.saveAndFlush(new Project("Company OS", null));
        Project other = projectRepository.saveAndFlush(new Project("Something else", null));

        save("first", project);
        save("second", project);
        save("elsewhere", other);
        taskRepository.saveAndFlush(new Task("unassigned", null, "OPEN", "LOW"));

        assertThat(taskRepository.findAllByProjectId(project.getId()))
                .extracting(Task::getTitle)
                .containsExactly("first", "second");
    }

    private void save(String title, Project project) {
        Task task = new Task(title, null, "OPEN", "HIGH");
        task.assignTo(project);
        taskRepository.saveAndFlush(task);
    }
}
