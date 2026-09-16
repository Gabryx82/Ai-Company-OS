package com.aicompany.backend.project;

import com.aicompany.backend.project.exception.ArchivedProjectIsImmutableException;
import com.aicompany.backend.support.Preconditions;
import com.aicompany.backend.project.exception.ProjectNameConflictException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.project.service.ProjectService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the service does when the database refuses a write.
 *
 * <p>This is the branch a concurrent create takes: two requests pass the
 * {@code exists} pre-check and the unique index stops the second one. Producing
 * that interleaving for real would need scheduling machinery out of proportion
 * to what is being asserted, so the failure the database would raise is handed
 * to the service directly. The index itself is exercised against real PostgreSQL
 * in {@link ProjectPersistenceTest}; what is pinned here is the translation.
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceConflictTest {

    @Mock
    private ProjectRepository repository;

    @InjectMocks
    private ProjectService service;

    @Test
    void aViolationOfTheNameIndexBecomesADomainConflict() {

        when(repository.existsByNormalisedName(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(Project.class)))
                .thenThrow(integrityViolation(ProjectRepository.NAME_UNIQUE_INDEX));

        assertThatThrownBy(() -> service.create("Company OS", null))
                .isInstanceOf(ProjectNameConflictException.class)
                .hasMessageContaining("Company OS");
    }

    @Test
    void anyOtherIntegrityViolationIsNotMaskedAsADuplicateName() {

        // The trap this guards: the first foreign key or new NOT NULL column a
        // later task adds must not surface as "that name is taken".
        DataIntegrityViolationException unrelated = integrityViolation("projects_owner_fk");

        when(repository.existsByNormalisedName(anyString())).thenReturn(false);
        when(repository.saveAndFlush(any(Project.class))).thenThrow(unrelated);

        assertThatThrownBy(() -> service.create("Company OS", null))
                .isSameAs(unrelated)
                .isNotInstanceOf(ProjectNameConflictException.class);
    }

    @Test
    void theSameTranslationAppliesOnUpdate() {

        // findByIdForUpdate, not findById: since TASK-004 every write path takes
        // the project row exclusively before reading its state (ADR-006 L1).
        when(repository.findByIdForUpdate(anyLong()))
                .thenReturn(Optional.of(new Project("Company OS", null)));
        when(repository.existsByNormalisedNameAndIdNot(anyString(), anyLong())).thenReturn(false);
        when(repository.saveAndFlush(any(Project.class)))
                .thenThrow(integrityViolation(ProjectRepository.NAME_UNIQUE_INDEX));

        assertThatThrownBy(() -> service.update(1L, "Renamed", null, Preconditions.at(0)))
                .isInstanceOf(ProjectNameConflictException.class);
    }

    @Test
    void updatingAnArchivedProjectIsRefusedBeforeAnythingIsWritten() {

        Project archived = new Project("Company OS", null);
        archived.archive();

        when(repository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(archived));
        when(repository.existsByNormalisedNameAndIdNot(anyString(), anyLong())).thenReturn(false);

        assertThatThrownBy(() -> service.update(1L, "Renamed", "new description", Preconditions.at(0)))
                .isInstanceOf(ArchivedProjectIsImmutableException.class);

        verify(repository, never()).saveAndFlush(any(Project.class));
        assertThat(archived.getName()).isEqualTo("Company OS");
        assertThat(archived.getDescription()).isNull();
    }

    /**
     * The shape Spring Data hands back when PostgreSQL rejects a write: the
     * driver error wrapped by Hibernate, wrapped by the exception translator.
     */
    private static DataIntegrityViolationException integrityViolation(String constraintName) {

        SQLException driverFailure = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"" + constraintName + "\"",
                "23505");

        return new DataIntegrityViolationException(
                "could not execute statement",
                new ConstraintViolationException("could not execute statement", driverFailure, constraintName));
    }
}
