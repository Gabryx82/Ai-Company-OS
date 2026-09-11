package com.aicompany.backend.project.service;

import com.aicompany.backend.project.exception.ProjectNameAlreadyExistsException;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import com.aicompany.backend.project.repository.ProjectRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

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

        if (repository.existsByNameIgnoreCase(name)) {
            throw new ProjectNameAlreadyExistsException(name);
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

        if (repository.existsByNameIgnoreCaseAndIdNot(name, id)) {
            throw new ProjectNameAlreadyExistsException(name);
        }

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
     */
    private Project saveGuardingUniqueName(Project project, String name) {
        try {
            return repository.saveAndFlush(project);
        } catch (DataIntegrityViolationException e) {
            throw new ProjectNameAlreadyExistsException(name);
        }
    }
}
