package com.aicompany.backend.project.repository;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * Name of the functional unique index created by {@code V2__create_projects.sql}.
     * The service matches on it to tell a name conflict from any other integrity
     * violation, so the two must stay in step.
     */
    String NAME_UNIQUE_INDEX = "projects_name_unique_idx";

    List<Project> findAllByStatusOrderByIdAsc(ProjectStatus status);

    List<Project> findAllByOrderByIdAsc();

    /**
     * Case-insensitive existence check, written with {@code lower(...)} on purpose.
     *
     * <p>The derived {@code IgnoreCase} keyword would generate {@code upper(name) =
     * upper(?)}, and {@code upper} is not the inverse of {@code lower} in
     * PostgreSQL: {@code upper('ı') = upper('I')} while {@code lower('ı') <>
     * lower('I')}. With {@code upper} this check rejected names the unique index —
     * the actual invariant, see ADR-004 §5 — allows, and it could not use that
     * index either, because the index is on {@code lower(name)}.
     */
    @Query("SELECT COUNT(p) > 0 FROM Project p WHERE LOWER(p.name) = LOWER(:name)")
    boolean existsByNormalisedName(@Param("name") String name);

    /** Same rule, excluding one project: renaming to its own name is not a conflict. */
    @Query("SELECT COUNT(p) > 0 FROM Project p WHERE LOWER(p.name) = LOWER(:name) AND p.id <> :id")
    boolean existsByNormalisedNameAndIdNot(@Param("name") String name, @Param("id") Long id);
}
