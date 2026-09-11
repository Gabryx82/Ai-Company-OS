package com.aicompany.backend.project.repository;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findAllByStatusOrderByIdAsc(ProjectStatus status);

    List<Project> findAllByOrderByIdAsc();

    /**
     * Case-insensitive, matching the functional unique index in V2. Used for the
     * friendly 409; the index is what actually guarantees uniqueness.
     */
    boolean existsByNameIgnoreCase(String name);

    boolean existsByNameIgnoreCaseAndIdNot(String name, Long id);
}
