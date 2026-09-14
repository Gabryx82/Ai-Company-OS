package com.aicompany.backend.project.repository;

import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.model.ProjectStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /**
     * The project row, locked exclusively. Rule L1 of ADR-006 section 4: whoever
     * <em>changes</em> a project's lifecycle state takes this before reading that
     * state, and holds it to commit.
     *
     * <p>Under READ COMMITTED a blocked {@code FOR UPDATE} re-reads the latest
     * committed row once it is let through, which is the whole mechanism: the
     * second of two concurrent archives finds ARCHIVED and raises the illegal
     * transition ADR-004 section 4 asks for, instead of reporting a second
     * success.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForUpdate(@Param("id") Long id);

    /**
     * The project row, locked shared. Rule L2: whoever <em>reads</em> a project's
     * lifecycle state in order to act on it takes this on every project row the
     * decision depends on, and holds it to commit.
     *
     * <p>Shared and not exclusive because two tasks being assigned to the same
     * active project are not in conflict with each other; only a lifecycle
     * transition conflicts with them. That distinction is the distinction between
     * the two roles, not an optimisation -- an exclusive lock here would
     * serialise every assignment per project and invent a conflict that does not
     * exist.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT p FROM Project p WHERE p.id = :id")
    Optional<Project> findByIdForShare(@Param("id") Long id);

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
