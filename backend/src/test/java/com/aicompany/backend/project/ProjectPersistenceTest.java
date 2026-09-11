package com.aicompany.backend.project;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round-trip persistence for projects against PostgreSQL, plus proof that the
 * database rejects on its own the rows the application should never write.
 */
class ProjectPersistenceTest extends AbstractPostgresTest {

    @Autowired
    private ProjectRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearProjects() {
        repository.deleteAll();
    }

    @Test
    void projectSurvivesAWriteAndReadCycle() {

        Project saved = repository.save(new Project("Company OS", "the control plane"));
        assertThat(saved.getId()).isNotNull();

        Project reloaded = repository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getName()).isEqualTo("Company OS");
        assertThat(reloaded.getDescription()).isEqualTo("the control plane");
        assertThat(reloaded.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @Test
    void timestampsAreSetOnCreateAndOnlyUpdatedAtMovesOnChange() {

        Project saved = repository.saveAndFlush(new Project("Company OS", null));

        Instant createdAt = saved.getCreatedAt();
        Instant firstUpdate = saved.getUpdatedAt();

        assertThat(createdAt).isNotNull();
        assertThat(firstUpdate).isNotNull().isAfterOrEqualTo(createdAt);

        saved.updateDetails("Company OS v2", "renamed");
        Project updated = repository.saveAndFlush(saved);

        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(firstUpdate);
    }

    @Test
    void archivedProjectIsStillStored() {

        Project saved = repository.saveAndFlush(new Project("Company OS", null));
        saved.archive();
        repository.saveAndFlush(saved);

        Project reloaded = repository.findById(saved.getId()).orElseThrow();

        // The whole point of ARCHIVED: the row is still there.
        assertThat(reloaded.getStatus()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void statusIsStoredAsTextNotAsAnOrdinal() {

        Project saved = repository.saveAndFlush(new Project("Company OS", null));

        String stored = jdbc.queryForObject(
                "SELECT status FROM projects WHERE id = ?", String.class, saved.getId());

        assertThat(stored).isEqualTo("ACTIVE");
    }

    @Test
    void listingByStatusSeparatesActiveFromArchived() {

        repository.saveAndFlush(new Project("Active one", null));

        Project archived = repository.saveAndFlush(new Project("Archived one", null));
        archived.archive();
        repository.saveAndFlush(archived);

        assertThat(repository.findAllByStatusOrderByIdAsc(ProjectStatus.ACTIVE))
                .extracting(Project::getName)
                .containsExactly("Active one");

        assertThat(repository.findAllByStatusOrderByIdAsc(ProjectStatus.ARCHIVED))
                .extracting(Project::getName)
                .containsExactly("Archived one");
    }

    @Test
    void databaseRejectsAProjectWithoutName() {

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO projects (name, status, created_at, updated_at) "
                        + "VALUES (NULL, 'ACTIVE', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsAStatusOutsideTheClosedSet() {

        // The enum is not the only guard: a row written outside the application
        // cannot introduce a status the domain cannot interpret.
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO projects (name, status, created_at, updated_at) "
                        + "VALUES ('Smuggled', 'DELETED', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsADuplicateNameRegardlessOfCase() {

        repository.saveAndFlush(new Project("Company OS", null));

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO projects (name, status, created_at, updated_at) "
                        + "VALUES ('company os', 'ACTIVE', now(), now())"))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void identifiersAreAssignedByTheDatabase() {

        Project first = repository.saveAndFlush(new Project("First", null));
        Project second = repository.saveAndFlush(new Project("Second", null));

        assertThat(first.getId()).isNotNull();
        assertThat(second.getId()).isNotNull().isNotEqualTo(first.getId());
    }
}
