package com.aicompany.backend.agent.repository;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentStatus;
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

    /**
     * The listing filtered by lifecycle state.
     *
     * <p>Took a {@code boolean} until {@code V8}; the column it derives from is
     * now {@link AgentStatus}, so the query is too. The HTTP contract is
     * unaffected -- {@code ?active=} is still a boolean, and the service is where
     * the two meet (ADR-012 §4).
     */
    List<Agent> findAllByStatusOrderByIdAsc(AgentStatus status);

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
     * The agent row, locked shared. Rule L2: whoever <em>reads</em> an agent's
     * lifecycle state in order to act on it takes this, and holds it to commit.
     *
     * <p>Shared and not exclusive for the reason the project side gives: two
     * tasks being given to the same active agent are not in conflict with each
     * other, and an exclusive lock here would serialise every assignment per
     * agent and invent a conflict that does not exist. What it does exclude is a
     * deactivation running alongside the decision, which is what makes the
     * guarantee "at the commit of this assignment, the agent <em>was</em> active"
     * rather than "was checked at some point".
     *
     * <p>Only the <strong>destination</strong> agent is taken this way. The agent
     * a task is leaving is not locked at all, because no rule depends on its
     * state -- ADR-010 D3 -- and a lock that protects nothing would only invent
     * another conflict.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT a FROM Agent a WHERE a.id = :id")
    Optional<Agent> findByIdForShare(@Param("id") Long id);

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
