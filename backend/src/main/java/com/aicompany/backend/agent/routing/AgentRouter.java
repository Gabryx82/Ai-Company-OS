package com.aicompany.backend.agent.routing;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentStatus;
import com.aicompany.backend.agent.repository.AgentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Which agents of the registry fit a piece of work (TD-08, ADR-016 §6).
 *
 * <p>What replaced {@code MasterOrchestrator}, and the difference is the point:
 * that class answered four hard-coded agent names, none of which existed in the
 * database, to four keywords. This one answers <strong>agents that exist and are
 * active</strong>, ranked by how much of the work's vocabulary their role,
 * specialization and name share, and says which words matched -- so the operator
 * can see why, and disagree.
 *
 * <p>Lexical and deterministic on purpose. It is a suggestion the operator
 * accepts by assigning the task; nothing is assigned here. A model-based router is
 * a later decision, and it would plug in behind this same answer.
 */
@Service
@Transactional(readOnly = true)
public class AgentRouter {

    /** Words that say nothing about which specialist is needed. */
    private static final Set<String> STOPWORDS = Set.of(
            "the", "and", "for", "with", "from", "into", "that", "this", "these", "those", "are", "was", "will",
            "have", "has", "not", "but", "all", "any", "can", "should", "must", "our", "your", "its", "one",
            "two", "new", "add", "make", "use", "using", "via", "per", "task", "work", "need", "needs",
            "del", "della", "dei", "delle", "con", "una", "uno", "che", "nel", "nella", "sul", "sulla");

    /**
     * Five, not four: the end-to-end smoke test of TASK-020 matched "post" (from
     * "POST /api/...") to "PostgreSQL" and suggested the database specialist for an
     * HTTP endpoint. Four letters are a prefix of too many unrelated words.
     */
    private static final int MINIMUM_PREFIX = 5;

    private final AgentRepository agents;

    public AgentRouter(AgentRepository agents) {
        this.agents = agents;
    }

    public record Suggestion(Long agentId, String name, String role, String specialization, String model,
                             int score, List<String> matchedTerms) {
    }

    /** Every active agent, best fit first; ties broken by id so the order is stable. */
    public List<Suggestion> suggest(String work) {

        Set<String> wanted = terms(work);
        List<Suggestion> suggestions = new ArrayList<>();

        for (Agent agent : agents.findAllByStatusOrderByIdAsc(AgentStatus.ACTIVE)) {
            Set<String> offered = terms(agent.getName() + " " + agent.getRole() + " " + agent.getSpecialization());
            List<String> matched = wanted.stream().filter(term -> offered.stream().anyMatch(o -> related(term, o)))
                    .sorted().toList();
            suggestions.add(new Suggestion(agent.getId(), agent.getName(), agent.getRole(),
                    agent.getSpecialization(), agent.getModel(), matched.size(), matched));
        }

        suggestions.sort(Comparator.comparingInt(Suggestion::score).reversed()
                .thenComparing(Suggestion::agentId));
        return suggestions;
    }

    /** Lower-cased words of three letters or more, without stopwords. */
    static Set<String> terms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null) {
            return terms;
        }
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3 && !STOPWORDS.contains(word)) {
                terms.add(word);
            }
        }
        return terms;
    }

    /**
     * Equal, or one a prefix of the other when both are long enough to mean
     * something: "database" and "databases", "react" and "reactive" are related;
     * "post" and "postgresql" are not.
     */
    static boolean related(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.length() < MINIMUM_PREFIX || b.length() < MINIMUM_PREFIX) {
            return false;
        }
        return a.startsWith(b) || b.startsWith(a);
    }
}
