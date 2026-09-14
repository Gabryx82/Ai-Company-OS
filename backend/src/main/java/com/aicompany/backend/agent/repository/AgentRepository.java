package com.aicompany.backend.agent.repository;

import com.aicompany.backend.agent.model.Agent;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AgentRepository extends JpaRepository<Agent, Long> {

    /**
     * Name of the functional unique index created by
     * {@code V4__add_agent_registry_columns.sql}. The service matches on it to
     * tell a name conflict from any other integrity violation, so the two must
     * stay in step.
     */
    String NAME_UNIQUE_INDEX = "agents_name_unique_idx";

    List<Agent> findAllByOrderByIdAsc();

    List<Agent> findAllByActiveOrderByIdAsc(boolean active);

    /**
     * The agent row, locked exclusively. Rule L1 of ADR-006 §4: whoever changes a
     * lifecycle state takes this before reading that state, and holds it to
     * commit.
     *
     * <p>Applied here because L7 makes the protocol universal, not because agents
     * are a contention hotspot. Without it, two concurrent deactivations both
     * report success instead of one 200 and one 409 -- which is TD-19 again, on a
     * third entity. "This case is harmless" is exactly the reasoning that
     * produced TD-25.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Agent a WHERE a.id = :id")
    Optional<Agent> findByIdForUpdate(@Param("id") Long id);

    /**
     * Case-insensitive existence check, written with {@code lower(...)} on
     * purpose. The derived {@code IgnoreCase} keyword generates
     * {@code upper(name) = upper(?)}, and {@code upper} is not the inverse of
     * {@code lower} in PostgreSQL -- see ADR-004 §5, where that mismatch was
     * rilievo F-1. It would also be unable to use the index, which is on
     * {@code lower(name)}.
     */
    @Query("SELECT COUNT(a) > 0 FROM Agent a WHERE LOWER(a.name) = LOWER(:name)")
    boolean existsByNormalisedName(@Param("name") String name);

    /** Same rule, excluding one agent: renaming to its own name is not a conflict. */
    @Query("SELECT COUNT(a) > 0 FROM Agent a WHERE LOWER(a.name) = LOWER(:name) AND a.id <> :id")
    boolean existsByNormalisedNameAndIdNot(@Param("name") String name, @Param("id") Long id);
}
