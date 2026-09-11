package com.aicompany.backend.project.service;

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

    @Transactional(readOnly = true)
    public Project findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new ProjectNotFoundException(id));
    }

    public Project update(Long id, String name, String description) {

        Project project = findById(id);

        if (repository.existsByNormalisedNameAndIdNot(name, id)) {
            throw new ProjectNameConflictException(name);
        }

        // Rejects an archived project; the rule is on the entity, not here.
        project.updateDetails(name, description);
        return saveGuardingUniqueName(project, name);
    }

    public Project archive(Long id) {
        Project project = findById(id);
        project.archive();
        return repository.save(project);
    }

    public Project restore(Long id) {
        Project project = findById(id);
        project.restore();
        return repository.save(project);
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
