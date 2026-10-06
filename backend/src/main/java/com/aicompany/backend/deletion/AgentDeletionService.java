package com.aicompany.backend.deletion;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.user.service.SecurityLog;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Deleting agents for real (PHASE 30, ADR-035 §2) -- the template ones included:
 * the base set is reinstallable, so it is not protected.
 *
 * <p>What goes with an agent and what stays, decided once here and shown before
 * the operator confirms ({@link #preview}):
 * <ul>
 *   <li><strong>its configuration</strong> -- prompt engineering, binding, the links
 *       to skills and software -- goes with it: it is the agent;</li>
 *   <li><strong>its tasks stay</strong>, unassigned: open ones wait for another
 *       agent, finished ones keep their history. A task in progress <em>blocks</em>
 *       the delete: work that is being done is not left without somebody
 *       responsible -- reassign it, or finish or stop it, first; or ask for
 *       {@link InProgress#STOP} and the delete stops them itself;</li>
 *   <li><strong>its runs stay</strong>, with the agent's name written into them
 *       (V28): the history keeps saying who did the work. A run still queued or
 *       running blocks the delete;</li>
 *   <li><strong>its sub-agents</strong> become top-level agents ({@code DETACH},
 *       the default, which destroys nothing) or are deleted with it
 *       ({@code DELETE}, said explicitly), under the same rules.</li>
 * </ul>
 *
 * <p><strong>Lock order.</strong> ADR-010 §3 orders the row classes
 * {@code tasks}, {@code projects}, {@code agents}. A delete writes tasks (it
 * unassigns them), so it locks them <em>before</em> the agents: first the tasks
 * that point at the agents, then the agents in id order, then it checks again
 * under the locks -- a task assigned or a run queued in between is seen there,
 * because assigning and launching take the agent's share lock, which waits for
 * this transaction's exclusive one.
 *
 * <p>Distinct from deactivating, which stays the reversible gesture (ADR-012).
 */
@Service
@Transactional
public class AgentDeletionService {

    /** What happens to the sub-agents of a deleted agent. */
    public enum SubAgents { DETACH, DELETE }

    /**
     * What happens to the tasks a deleted agent has in progress: {@code BLOCK}
     * (the default) refuses the delete; {@code STOP} sends them back to the
     * queue, unassigned -- the STOP edge, the recovery path ADR-014 names for an
     * agent that stopped working. A run still queued or running always blocks.
     */
    public enum InProgress { BLOCK, STOP }

    private static final Set<String> UNFINISHED_RUNS = Set.of("QUEUED", "RUNNING");

    private final AgentRepository agents;
    private final JdbcTemplate jdbc;
    private final SecurityLog log;

    public AgentDeletionService(AgentRepository agents, JdbcTemplate jdbc, SecurityLog log) {
        this.agents = agents;
        this.jdbc = jdbc;
        this.log = log;
    }

    // --- preview -------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public DeletionPreview preview(Collection<Long> ids, SubAgents policy, InProgress inProgress) {
        requireSome(ids);
        Map<Long, AgentRow> all = rows();
        for (Long id : ids) {
            if (!all.containsKey(id)) {
                throw new AgentNotFoundException(id);
            }
        }
        return impact(all, targets(all, ids, policy), inProgress);
    }

    // --- delete --------------------------------------------------------------------------

    /** One agent, under its tag (ADR-009). */
    public void delete(Long id, SubAgents policy, InProgress inProgress, String by, Precondition precondition) {
        deleteAll(Map.of(id, precondition), policy, inProgress, by);
    }

    /**
     * Several agents at once -- the operator's selection, up to the whole set.
     * Each one is named with the tag the caller read, checked under its lock:
     * a selection made on stale data is a 412 for the whole batch, never a
     * partial delete.
     */
    public void deleteAll(Map<Long, Precondition> selection, SubAgents policy, InProgress inProgress, String by) {
        requireSome(selection.keySet());
        Map<Long, AgentRow> all = rows();
        for (Long id : selection.keySet()) {
            if (!all.containsKey(id)) {
                throw new AgentNotFoundException(id);
            }
        }
        Set<Long> doomed = targets(all, selection.keySet(), policy);

        // 1. tasks first (ADR-010 §3): every task that points at an agent being deleted.
        lockTasksOf(doomed);

        // 2. then the agents, in id order: the ones deleted and the sub-agents that will be detached.
        Set<Long> toLock = new TreeSet<>(doomed);
        all.values().stream().filter(a -> a.parentId() != null && doomed.contains(a.parentId()))
                .forEach(a -> toLock.add(a.id()));
        Map<Long, Agent> locked = new LinkedHashMap<>();
        for (Long id : toLock) {
            agents.findByIdForUpdate(id).ifPresent(agent -> locked.put(id, agent));
        }
        for (Map.Entry<Long, Precondition> chosen : selection.entrySet()) {
            Agent agent = locked.get(chosen.getKey());
            if (agent == null) {
                throw new AgentNotFoundException(chosen.getKey());
            }
            chosen.getValue().requireSatisfiedBy(agent.getVersion());
        }

        // 3. again, under the locks: the hierarchy and the work may have moved since the first read.
        Map<Long, AgentRow> current = rows();
        Set<Long> confirmed = targets(current, selection.keySet(), policy);
        if (!confirmed.equals(doomed)) {
            lockTasksOf(confirmed);
        }
        DeletionPreview impact = impact(current, confirmed, inProgress).requireAllowed();

        String names = confirmed.stream().map(id -> "«" + current.get(id).name() + "»").collect(Collectors.joining(", "));
        for (Long id : confirmed) {
            String name = current.get(id).name();
            if (inProgress == InProgress.STOP) {
                jdbc.update("UPDATE tasks SET status = 'OPEN' WHERE agent_id = ? AND status = 'IN_PROGRESS'", id);
            }
            jdbc.update("UPDATE tasks SET agent_id = NULL, version = version + 1 WHERE agent_id = ?", id);
            jdbc.update("UPDATE task_runs SET agent_name = ?, agent_id = NULL WHERE agent_id = ?", name, id);
            jdbc.update("DELETE FROM agent_resources WHERE agent_id = ?", id);
            jdbc.update("DELETE FROM agent_software WHERE agent_id = ?", id);
        }
        String inDoomed = placeholders(confirmed.size());
        Object[] doomedIds = confirmed.toArray();
        // Sub-agents that are not deleted lose their parent and become top-level agents.
        jdbc.update("UPDATE agents SET parent_id = NULL, version = version + 1, updated_at = now() "
                + "WHERE parent_id IN (" + inDoomed + ") AND id NOT IN (" + inDoomed + ")",
                concat(doomedIds, doomedIds));
        // Inside the deleted set the links go first, so the rows can go in any order.
        jdbc.update("UPDATE agents SET parent_id = NULL WHERE id IN (" + inDoomed + ")", doomedIds);
        jdbc.update("DELETE FROM agents WHERE id IN (" + inDoomed + ")", doomedIds);

        log.record(SecurityLog.DATA_DELETED, by, null, "agents " + names + " (" + policy + " sub-agents, "
                + inProgress + " in progress): "
                + String.join(" ", impact.detaches()));
    }

    // --- the rules, shared by preview and delete ------------------------------------------

    private record AgentRow(Long id, String name, Long parentId) {
    }

    private Map<Long, AgentRow> rows() {
        Map<Long, AgentRow> out = new LinkedHashMap<>();
        jdbc.query("SELECT id, name, parent_id FROM agents ORDER BY id", rs -> {
            out.put(rs.getLong("id"), new AgentRow(rs.getLong("id"), rs.getString("name"),
                    rs.getObject("parent_id", Long.class)));
        });
        return out;
    }

    /** The agents chosen and, with {@code DELETE}, every descendant. Cycles cannot loop: each id is visited once. */
    private static Set<Long> targets(Map<Long, AgentRow> all, Collection<Long> chosen, SubAgents policy) {
        Set<Long> out = new TreeSet<>(chosen);
        if (policy == SubAgents.DELETE) {
            List<Long> frontier = new ArrayList<>(chosen);
            while (!frontier.isEmpty()) {
                Long parent = frontier.remove(frontier.size() - 1);
                for (AgentRow row : all.values()) {
                    if (parent.equals(row.parentId()) && out.add(row.id())) {
                        frontier.add(row.id());
                    }
                }
            }
        }
        return out;
    }

    private DeletionPreview impact(Map<Long, AgentRow> all, Set<Long> doomed, InProgress inProgress) {
        DeletionPreview.Builder preview = DeletionPreview.of("AGENT");
        doomed.forEach(id -> preview.subject(id, all.get(id).name()));
        String in = placeholders(doomed.size());
        Object[] ids = doomed.toArray();

        preview.deletes(doomed.size() == 1 ? "L'agente e la sua configurazione: prompt, direttive, modello, execution target."
                : doomed.size() + " agenti e la loro configurazione: prompt, direttive, modello, execution target.");
        int resources = count("SELECT count(*) FROM agent_resources WHERE agent_id IN (" + in + ")", ids);
        int software = count("SELECT count(*) FROM agent_software WHERE agent_id IN (" + in + ")", ids);
        if (resources + software > 0) {
            preview.deletes(resources + " collegamenti a skill e strumenti, " + software + " a software: le skill "
                    + "e i software restano nel catalogo.");
        }

        List<Map<String, Object>> working = jdbc.queryForList("SELECT t.id, t.title, a.name FROM tasks t JOIN agents a "
                + "ON a.id = t.agent_id WHERE t.agent_id IN (" + in + ") AND t.status = 'IN_PROGRESS' ORDER BY t.id", ids);
        if (inProgress == InProgress.STOP) {
            if (!working.isEmpty()) {
                preview.detaches(working.size() + " task in corso tornano «Da fare», senza agente: "
                        + working.stream().map(t -> "«" + t.get("title") + "»").collect(Collectors.joining(", ")) + ".");
            }
        } else {
            for (Map<String, Object> task : working) {
                preview.blocker("«" + task.get("name") + "» lavora alla task in corso «" + task.get("title") + "» (#"
                        + task.get("id") + "): riassegnala, completala o fermala prima.");
            }
        }
        for (Map<String, Object> run : jdbc.queryForList("SELECT r.task_id, a.name FROM task_runs r JOIN agents a "
                + "ON a.id = r.agent_id WHERE r.agent_id IN (" + in + ") AND r.status IN ('QUEUED','RUNNING') ORDER BY r.id", ids)) {
            preview.blocker("«" + run.get("name") + "» ha un'esecuzione in corso sulla task #" + run.get("task_id")
                    + ": attendi che finisca.");
        }

        int open = count("SELECT count(*) FROM tasks WHERE agent_id IN (" + in + ") AND status = 'OPEN'", ids);
        int done = count("SELECT count(*) FROM tasks WHERE agent_id IN (" + in + ") AND status = 'DONE'", ids);
        if (open > 0) {
            preview.detaches(open + " task da fare restano, senza agente: si riassegnano quando vuoi.");
        }
        if (done > 0) {
            preview.detaches(done + " task completate restano, senza agente.");
        }
        int frozen = count("SELECT count(*) FROM tasks t JOIN projects p ON p.id = t.project_id WHERE t.agent_id IN ("
                + in + ") AND p.status = 'ARCHIVED'", ids);
        if (frozen > 0) {
            preview.detaches(frozen + " di queste sono in progetti archiviati: perdono anche loro l'assegnazione.");
        }
        int runs = count("SELECT count(*) FROM task_runs WHERE agent_id IN (" + in + ")", ids);
        if (runs > 0) {
            preview.keeps(runs + " esecuzioni restano nello storico, con il nome dell'agente.");
        }
        List<String> orphans = all.values().stream()
                .filter(a -> a.parentId() != null && doomed.contains(a.parentId()) && !doomed.contains(a.id()))
                .map(a -> "«" + a.name() + "»").toList();
        if (!orphans.isEmpty()) {
            preview.detaches("Sottoagenti che diventano agenti principali: " + String.join(", ", orphans) + ".");
        }
        List<String> template = jdbc.queryForList("SELECT name FROM agents WHERE origin <> 'USER' AND id IN (" + in + ")",
                String.class, ids);
        if (!template.isEmpty()) {
            preview.keeps("Gli agenti del set iniziale si reinstallano da «Da template» quando vuoi; non tornano da soli.");
        }
        return preview.build();
    }

    private void lockTasksOf(Set<Long> agentIds) {
        jdbc.queryForList("SELECT id FROM tasks WHERE agent_id IN (" + placeholders(agentIds.size()) + ") ORDER BY id FOR UPDATE",
                Long.class, agentIds.toArray());
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static String placeholders(int n) {
        return String.join(",", java.util.Collections.nCopies(Math.max(n, 1), "?"));
    }

    private static Object[] concat(Object[] a, Object[] b) {
        Object[] out = new Object[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static void requireSome(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new RequestValidationException("ids", "must name at least one agent");
        }
        if (ids.size() > 500) {
            throw new RequestValidationException("ids", "must name at most 500 agents");
        }
        if (new LinkedHashSet<>(ids).size() != ids.size()) {
            throw new RequestValidationException("ids", "must not repeat an agent");
        }
    }
}
