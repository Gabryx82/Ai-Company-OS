package com.aicompany.backend.persistence;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskPriority;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip persistence against PostgreSQL, and proof that the database itself
 * rejects rows the application should never write.
 */
class TaskPersistenceTest extends AbstractPostgresTest {

    @Autowired
    private TaskRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void taskSurvivesAWriteAndReadCycle() {

        Task saved = repository.save(new Task("Persisted task", "with a description", TaskStatus.OPEN, TaskPriority.HIGH));
        assertThat(saved.getId()).isNotNull();

        Task reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getTitle()).isEqualTo("Persisted task");
        assertThat(reloaded.getDescription()).isEqualTo("with a description");
        assertThat(reloaded.getStatus()).isEqualTo(TaskStatus.OPEN);
        assertThat(reloaded.getPriority()).isEqualTo(TaskPriority.HIGH);
    }

    @Test
    void identifiersAreAssignedByTheDatabase() {

        Task first = repository.save(new Task("First", null, TaskStatus.OPEN, TaskPriority.LOW));
        Task second = repository.save(new Task("Second", null, TaskStatus.OPEN, TaskPriority.LOW));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull().isNotEqualTo(first.getId());
    }

    @Test
    void databaseRejectsATaskWithoutTitle() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tasks (title, description, status, priority) VALUES (NULL, NULL, 'OPEN', 'LOW')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsATaskWithoutStatus() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tasks (title, description, status, priority) VALUES ('t', NULL, NULL, 'LOW')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
