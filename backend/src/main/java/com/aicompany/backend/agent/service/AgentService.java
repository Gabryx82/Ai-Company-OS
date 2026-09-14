package com.aicompany.backend.agent.service;

import com.aicompany.backend.agent.exception.AgentNameConflictException;
import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * Application service for the agent registry.
 *
 * <p>Lifecycle rules live on {@link Agent}; this class owns transactions, lookup
 * and the uniqueness contract. The shape deliberately mirrors
 * {@code ProjectService}: the two registries answer the same questions, and
 * answering them differently would give the system two domain dialects.
 */
@Service
@Transactional
public class AgentService {

    private final AgentRepository repository;

    public AgentService(AgentRepository repository) {
        this.repository = repository;
    }

    public Agent create(String name, String role, String specialization) {

        if (repository.existsByNormalisedName(name)) {
            throw new AgentNameConflictException(name);
        }

        return saveGuardingUniqueName(new Agent(name, role, specialization), name);
    }

    /**
     * Read-only, and without a lock: rule L6. A listing never delays a lifecycle
     * transition and a transition never delays a listing.
     */
    @Transactional(readOnly = true)
    public List<Agent> findAll(Boolean active) {
        return active == null
                ? repository.findAllByOrderByIdAsc()
                : repository.findAllByActiveOrderByIdAsc(active);
    }

    @Transactional(readOnly = true)
    public Agent findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new AgentNotFoundException(id));
    }

    public Agent update(Long id, String name, String role, String specialization) {

        Agent agent = lockForWrite(id);

        if (repository.existsByNormalisedNameAndIdNot(name, id)) {
            throw new AgentNameConflictException(name);
        }

        // Rejects an inactive agent; the rule is on the entity, not here.
        agent.updateDetails(name, role, specialization);
        return saveGuardingUniqueName(agent, name);
    }

    public Agent deactivate(Long id) {
        Agent agent = lockForWrite(id);
        agent.deactivate();
        return repository.save(agent);
    }

    public Agent activate(Long id) {
        Agent agent = lockForWrite(id);
        agent.activate();
        return repository.save(agent);
    }

    /**
     * The lookup every write path uses: rule L1, an exclusive lock on the agent
     * row taken before its state is read and held to commit.
     *
     * <p>Private and without a {@code @Transactional} of its own, for the reason
     * TD-24 taught on the project side: a private method cannot carry a
     * transactional attribute, so a write path cannot come to depend on
     * self-invocation quietly discarding a read-only flag.
     */
    private Agent lockForWrite(Long id) {
        return repository.findByIdForUpdate(id).orElseThrow(() -> new AgentNotFoundException(id));
    }

    /**
     * The {@code exists} check above produces a readable conflict in the ordinary
     * case. It is not a guarantee: two concurrent requests can both pass it, and
     * only the unique index stops the second. Translating that failure here keeps
     * the API contract identical either way (ADR-004 §5).
     *
     * <p>Only a violation of the name index is translated. Reporting every
     * integrity violation as a duplicate name would turn the first foreign key or
     * new NOT NULL column a later task adds into a misleading 409.
     */
    private Agent saveGuardingUniqueName(Agent agent, String name) {
        try {
            return repository.saveAndFlush(agent);
        } catch (DataIntegrityViolationException e) {
            if (violatesNameUniqueIndex(e)) {
                throw new AgentNameConflictException(name);
            }
            throw e;
        }
    }

    private static boolean violatesNameUniqueIndex(Throwable failure) {

        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {

            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && AgentRepository.NAME_UNIQUE_INDEX.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }

            String message = cause.getMessage();
            if (message != null
                    && message.toLowerCase(Locale.ROOT).contains(AgentRepository.NAME_UNIQUE_INDEX)) {
                return true;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return false;
    }
}
