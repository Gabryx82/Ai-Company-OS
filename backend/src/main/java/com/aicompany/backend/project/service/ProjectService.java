package com.aicompany.backend.project.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.exception.ProjectNameConflictException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.repository.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Application service for the project registry.
 *
 * <p>Lifecycle rules live on {@link Project}; this class owns transactions,
 * lookup and the uniqueness contract.
 */
@Service
@Transactional
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public Project create(String name, String description) {

        if (repository.existsByNormalisedName(name)) {
            throw new ProjectNameConflictException(name);
        }

        return saveGuardingUniqueName(new Project(name, description), name);
    }

    @Transactional(readOnly = true)
    public List<Project> findAll(ProjectStatus status) {
        return status == null
                ? repository.findAllByOrderByIdAsc()
                : repository.findAllByStatusOrderByIdAsc(status);
    }

    /**
     * Read-only lookup, for reads. No lock: a GET never delays a lifecycle
     * transition and a transition never delays a GET (rule L6).
     *
     * <p>Not called from the write paths in this class -- they use
     * {@link #lockForWrite}, see TD-24 there.
     */
    @Transactional(readOnly = true)
    public Project findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }

    public Project update(Long id, String name, String description, Precondition precondition) {

        Project project = lockForWrite(id, precondition);

        if (repository.existsByNormalisedNameAndIdNot(name, id)) {
            throw new ProjectNameConflictException(name);
        }

        // Rejects an archived project; the rule is on the entity, not here.
        project.updateDetails(name, description);
        return saveGuardingUniqueName(project, name);
    }

    public Project archive(Long id, Precondition precondition) {
        Project project = lockForWrite(id, precondition);
        project.archive();
        return repository.saveAndFlush(project);
    }

    public Project restore(Long id, Precondition precondition) {
        Project project = lockForWrite(id, precondition);
        project.restore();
        return repository.saveAndFlush(project);
    }

    /**
     * The lookup every write path uses: rule L1 of ADR-006 §4, an exclusive lock
     * on the project row taken before its state is read and held to commit.
     *
     * <p>Two concurrent archives now serialise here. The second one is let
     * through only after the first commits and -- under READ COMMITTED, where a
     * blocked {@code FOR UPDATE} re-reads the latest committed row -- finds
     * ARCHIVED, so {@link Project#archive()} raises the illegal transition that
     * ADR-004 §4 asks for. Before this lock existed both reported success, which
     * is what TD-19 recorded.
     *
     * <p><strong>Private, and with no {@code @Transactional} of its own, on
     * purpose.</strong> The write paths used to call the public
     * {@code readOnly = true} {@link #findById} through {@code this.}, and worked
     * only because self-invocation bypasses the proxy and with it the read-only
     * flag -- TD-24. A private method cannot carry a transactional attribute at
     * all, so that failure mode is no longer expressible here, not merely absent.
     * A structural test asserts it.
     */
    private Project lockForWrite(Long id, Precondition precondition) {

        Project project = repository.findByIdForUpdate(id)
                .orElseThrow(() -> new ProjectNotFoundException(id));

        // P1: after the lock, before any rule reads the row. Taking the
        // precondition as a parameter rather than fetching it from somewhere is
        // what makes it unskippable -- a write path that forgot it would not
        // compile.
        precondition.requireSatisfiedBy(project.getVersion());
        return project;
    }

    /**
     * The {@code exists} check above is what produces a readable 409 in the
     * ordinary case. It is not a guarantee: two concurrent requests can both pass
     * it and only the unique index will stop the second one. Translating that
     * failure here keeps the API contract identical either way.
     *
     * <p>Only a violation of {@code projects_name_unique_idx} is translated.
     * Reporting every {@link DataIntegrityViolationException} as a duplicate name
     * would turn the first foreign key or new NOT NULL column into a misleading
     * 409, so anything else propagates untouched.
     */
    private Project saveGuardingUniqueName(Project project, String name) {
        try {
            return repository.saveAndFlush(project);
        } catch (DataIntegrityViolationException e) {
            if (violatesNameUniqueIndex(e)) {
                throw new ProjectNameConflictException(name);
            }
            throw e;
        }
    }

    /**
     * Walks the cause chain looking for the name index. Hibernate exposes the
     * constraint name it extracted from the driver; the message check behind it
     * is a fallback for the cases where the dialect hands back nothing.
     */
    private static boolean violatesNameUniqueIndex(Throwable failure) {

        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {

            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && ProjectRepository.NAME_UNIQUE_INDEX.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }

            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(ProjectRepository.NAME_UNIQUE_INDEX)) {
                return true;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return false;
    }
}
