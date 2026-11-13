package com.aicompany.backend.sync;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.model.AgentOrigin;
import com.aicompany.backend.agent.model.AgentStatus;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.ProblemException;
import com.aicompany.backend.harness.hub.SkillHubService;
import com.aicompany.backend.harness.library.Essentials;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.harness.service.HarnessService;
import com.aicompany.backend.sync.AgentPublications.Publication;
import com.aicompany.backend.sync.AgentSyncConnector.ExternalAgent;
import com.aicompany.backend.sync.AgentSyncConnector.Status;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * PHASE 37 (ADR-043): one ecosystem. AI Company OS is where agents are made; this
 * service says how each of them stands in each tool around it -- OpenClaw, Open WebUI,
 * LiteLLM -- and brings the tools up to date when the operator asks.
 *
 * <p>The state is computed, never stored as a word: a publication row keeps the
 * fingerprint of what was written, and an agent is behind when what would be written
 * now has another one. Nothing is published on its own initiative: a tool's
 * configuration is the operator's, and it changes when they press the button.
 *
 * <p>No transaction is held while a tool is written -- a CLI takes seconds -- and the
 * row is recorded after the tool accepted the agent, so that a failure leaves the
 * state "not published", which is true.
 */
@Service
public class AgentSyncService {

    public enum AgentSyncState {
        /** The tool holds the agent as it is now. */
        IN_SYNC,
        /** The tool holds the agent as it was: it changed here since. */
        BEHIND,
        /** Can be published, and was not. */
        NOT_PUBLISHED,
        /** The tool cannot hold this agent; {@code detail} says why. */
        NOT_APPLICABLE,
        /** Published, and the tool no longer has it. */
        MISSING,
        /** An agent the tool had before: linked, never rewritten. */
        ADOPTED,
        /** The tool cannot be asked right now. */
        UNAVAILABLE,
        /** ADR-046: the tool's agents are being read, and were never read before: ask again in a few seconds. */
        READING
    }

    /**
     * @param checkedAt  ADR-046: when the tool's agents were read; null when they were not (the tool cannot be
     *                   asked, or the first reading is under way)
     * @param refreshing a reading is under way: the console asks again in a few seconds
     */
    public record AgentSyncTool(String key, String name, String shape, String availability, String detail,
                           @Nullable String hint, boolean imports, @Nullable Instant checkedAt, boolean refreshing) {
    }

    public record AgentSyncCell(String tool, AgentSyncState state, @Nullable String externalId, @Nullable String detail,
                       @Nullable Instant publishedAt) {
    }

    public record AgentSyncRow(Long agentId, String name, String role, List<AgentSyncCell> cells) {
    }

    /** An agent a tool has of its own, that no agent here stands for yet. */
    public record ImportableAgent(String tool, String externalId, String name, @Nullable String description) {
    }

    /** @param pending how many cells a synchronisation would write */
    public record AgentSyncOverview(List<AgentSyncTool> tools, List<AgentSyncRow> agents, List<ImportableAgent> importable, int pending) {
    }

    public enum AgentSyncOutcome { PUBLISHED, UPDATED, UNCHANGED, SKIPPED, FAILED }

    public record AgentSyncResult(Long agentId, String agent, String tool, AgentSyncOutcome outcome, @Nullable String externalId,
                         String detail, List<String> notes) {
    }

    public record AgentSyncReport(List<AgentSyncResult> results, int written, int failed) {
    }

    public record ImportedAgent(Long agentId, String name, String tool, String externalId) {
    }

    private final Map<String, AgentSyncConnector> connectors = new LinkedHashMap<>();
    private final AgentRepository agents;
    private final HarnessService harness;
    private final SkillLibrary library;
    private final SkillHubService hub;
    private final AgentPublications publications;
    private final TransactionTemplate transactions;
    private final int essentialLimit;
    /** ADR-046: where the tools are asked together for an overview. */
    private final ExecutorService together = Executors.newVirtualThreadPerTaskExecutor();

    public AgentSyncService(List<AgentSyncConnector> connectors, AgentRepository agents, HarnessService harness,
                            SkillLibrary library, SkillHubService hub, AgentPublications publications,
                            TransactionTemplate transactions,
                            @Value("${aicos.runs.skill-essential-chars:1500}") int essentialLimit) {
        // A fixed order, the one the console shows: the tool with agents of its own first.
        List<String> order = List.of(OpenClawConnector.KEY, OpenWebUiConnector.KEY, LiteLlmConnector.KEY);
        connectors.stream().sorted(java.util.Comparator.comparingInt(c -> {
            int at = order.indexOf(c.key());
            return at < 0 ? order.size() : at;
        })).forEach(c -> this.connectors.put(c.key(), c));
        this.agents = agents;
        this.harness = harness;
        this.library = library;
        this.hub = hub;
        this.publications = publications;
        this.transactions = transactions;
        this.essentialLimit = essentialLimit;
    }

    @PreDestroy
    void stop() {
        together.shutdownNow();
    }

    // --- reading ------------------------------------------------------------------------

    /**
     * What a tool answered when it was asked once: its status and, when available, its agents -- as read
     * {@code at}, or not yet known while a first reading is under way (ADR-046).
     */
    private record Asked(AgentSyncConnector connector, Status status, Map<String, ExternalAgent> agents,
                         @Nullable Instant at, boolean refreshing) {
        boolean has(String externalId) {
            return agents.containsKey(externalId);
        }

        boolean known() {
            return at != null;
        }
    }

    /**
     * How every agent stands in every tool. ADR-046: the tools are asked together, and none is waited for --
     * OpenClaw answers with its last reading and renews it behind (its command takes 12-60 s); what was never
     * read is {@link AgentSyncState#READING}, and the console asks again.
     */
    public AgentSyncOverview overview() {
        List<CompletableFuture<Asked>> asking = connectors.values().stream()
                .map(connector -> CompletableFuture.supplyAsync(() -> look(connector), together)).toList();
        List<Asked> asked = asking.stream().map(CompletableFuture::join).toList();
        List<Publication> rows = publications.all();
        Map<String, Publication> byAgentAndTool = new HashMap<>();
        rows.forEach(p -> byAgentAndTool.put(p.agentId() + "/" + p.tool(), p));

        List<AgentSyncRow> out = new ArrayList<>();
        int pending = 0;
        for (HarnessService.AgentHarness own : harness.all()) {
            if (own.agent().getStatus() != AgentStatus.ACTIVE) {
                continue;
            }
            AgentProfile profile = profile(own);
            List<AgentSyncCell> cells = new ArrayList<>();
            for (Asked tool : asked) {
                AgentSyncCell cell = cell(profile, tool, byAgentAndTool.get(profile.id() + "/" + tool.connector().key()));
                if (cell.state() == AgentSyncState.NOT_PUBLISHED || cell.state() == AgentSyncState.BEHIND
                        || cell.state() == AgentSyncState.MISSING) {
                    pending++;
                }
                cells.add(cell);
            }
            out.add(new AgentSyncRow(profile.id(), profile.name(), profile.role(), cells));
        }

        List<ImportableAgent> importable = new ArrayList<>();
        Set<String> linked = rows.stream().map(p -> p.tool() + "/" + p.externalId()).collect(Collectors.toSet());
        for (Asked tool : asked) {
            if (!tool.connector().imports() || !tool.known()) {
                continue;
            }
            tool.agents().values().stream()
                    .filter(a -> !a.ours() && !linked.contains(tool.connector().key() + "/" + a.externalId()))
                    .forEach(a -> importable.add(new ImportableAgent(tool.connector().key(), a.externalId(), a.name(), a.description())));
        }
        return new AgentSyncOverview(asked.stream().map(AgentSyncService::view).toList(), out, importable, pending);
    }

    /** For a page: the tool's status, and its agents as they are known now, without waiting for a slow tool. */
    private Asked look(AgentSyncConnector connector) {
        Status status;
        try {
            status = connector.status();
        } catch (RuntimeException e) {
            status = new Status(AgentSyncConnector.Availability.UNREACHABLE, said(e), null);
        }
        if (!status.available()) {
            return new Asked(connector, status, Map.of(), null, false);
        }
        AgentSyncConnector.Reading reading;
        try {
            reading = connector.reading();
        } catch (RuntimeException e) {
            return new Asked(connector, new Status(AgentSyncConnector.Availability.UNREACHABLE, said(e), "Riprova fra poco."),
                    Map.of(), null, false);
        }
        if (!reading.known() && !reading.refreshing() && reading.problem() != null) {
            return new Asked(connector, new Status(AgentSyncConnector.Availability.UNREACHABLE, reading.problem(),
                    "Riprova fra poco."), Map.of(), null, false);
        }
        Map<String, ExternalAgent> found = new LinkedHashMap<>();
        reading.agents().forEach(a -> found.put(a.externalId(), a));
        return new Asked(connector, status, found, reading.at(), reading.refreshing());
    }

    /** For a write: the tool's status and its agents as they are now -- a write needs them true. */
    private Asked ask(AgentSyncConnector connector) {
        Status status;
        try {
            status = connector.status();
        } catch (RuntimeException e) {
            status = new Status(AgentSyncConnector.Availability.UNREACHABLE, said(e), null);
        }
        Map<String, ExternalAgent> found = new LinkedHashMap<>();
        if (status.available()) {
            try {
                connector.list().forEach(a -> found.put(a.externalId(), a));
            } catch (RuntimeException e) {
                status = new Status(AgentSyncConnector.Availability.UNREACHABLE, said(e), "Riprova fra poco.");
            }
        }
        return new Asked(connector, status, found, Instant.now(), false);
    }

    private static AgentSyncCell cell(AgentProfile profile, Asked tool, @Nullable Publication row) {
        String key = tool.connector().key();
        String externalId = row == null ? null : row.externalId();
        Instant at = row == null ? null : row.publishedAt();
        if (!tool.status().available()) {
            return new AgentSyncCell(key, AgentSyncState.UNAVAILABLE, externalId, tool.status().detail(), at);
        }
        if (!tool.known()) {
            return new AgentSyncCell(key, AgentSyncState.READING, externalId, "Leggo gli agenti di " + tool.connector().name()
                    + ": i suoi comandi impiegano fino a un minuto.", at);
        }
        if (row == null) {
            Optional<String> why = tool.connector().notApplicable(profile);
            return why.map(reason -> new AgentSyncCell(key, AgentSyncState.NOT_APPLICABLE, null, reason, null))
                    .orElseGet(() -> new AgentSyncCell(key, AgentSyncState.NOT_PUBLISHED, null, null, null));
        }
        if (!tool.has(row.externalId())) {
            return new AgentSyncCell(key, AgentSyncState.MISSING, externalId, tool.connector().name() + " non ha più «" + row.externalId() + "».", at);
        }
        if (!row.owned()) {
            return new AgentSyncCell(key, AgentSyncState.ADOPTED, externalId, "È un agente di " + tool.connector().name()
                    + ": i suoi file e la sua configurazione restano suoi.", at);
        }
        Optional<String> why = tool.connector().notApplicable(profile);
        if (why.isPresent()) {
            return new AgentSyncCell(key, AgentSyncState.BEHIND, externalId, why.get(), at);
        }
        boolean same = tool.connector().fingerprint(profile).equals(row.fingerprint());
        return new AgentSyncCell(key, same ? AgentSyncState.IN_SYNC : AgentSyncState.BEHIND, externalId,
                same ? null : "L'agente è cambiato dopo l'ultima pubblicazione.", at);
    }

    private static AgentSyncTool view(Asked tool) {
        AgentSyncConnector connector = tool.connector();
        Status status = tool.status();
        return new AgentSyncTool(connector.key(), connector.name(), connector.shape(), status.availability().name(),
                status.detail(), status.hint(), connector.imports(), tool.at(), tool.refreshing());
    }

    // --- writing ------------------------------------------------------------------------

    /**
     * Brings the tools up to date.
     *
     * @param agentIds the agents to publish, or null for every active one
     * @param tools    the tools to write, or null for every one that is available
     */
    public AgentSyncReport publish(@Nullable List<Long> agentIds, @Nullable List<String> tools, String by) {
        List<AgentSyncConnector> chosen = tools == null || tools.isEmpty() ? List.copyOf(connectors.values())
                : tools.stream().distinct().map(this::connector).toList();
        boolean everyTool = tools == null || tools.isEmpty();
        List<HarnessService.AgentHarness> wanted;
        if (agentIds == null || agentIds.isEmpty()) {
            wanted = harness.all().stream().filter(h -> h.agent().getStatus() == AgentStatus.ACTIVE).toList();
        } else {
            wanted = agentIds.stream().distinct().map(harness::of).toList();
        }

        List<AgentSyncResult> results = new ArrayList<>();
        for (AgentSyncConnector connector : chosen) {
            Asked tool = ask(connector);
            if (!tool.status().available()) {
                // A tool nobody named and that is not there is not a failure of the synchronisation: it is said once.
                if (!everyTool) {
                    wanted.forEach(h -> results.add(new AgentSyncResult(h.agent().getId(), h.agent().getName(), connector.key(),
                            AgentSyncOutcome.SKIPPED, null, tool.status().detail(), List.of())));
                }
                continue;
            }
            for (HarnessService.AgentHarness own : wanted) {
                results.add(publishOne(own, tool, by));
            }
        }
        int written = (int) results.stream().filter(r -> r.outcome() == AgentSyncOutcome.PUBLISHED || r.outcome() == AgentSyncOutcome.UPDATED).count();
        int failed = (int) results.stream().filter(r -> r.outcome() == AgentSyncOutcome.FAILED).count();
        return new AgentSyncReport(results, written, failed);
    }

    private AgentSyncResult publishOne(HarnessService.AgentHarness own, Asked tool, String by) {
        AgentSyncConnector connector = tool.connector();
        Agent agent = own.agent();
        AgentProfile profile = profile(own);
        Optional<Publication> row = publications.find(agent.getId(), connector.key());
        if (row.isPresent() && !row.get().owned()) {
            return new AgentSyncResult(agent.getId(), agent.getName(), connector.key(), AgentSyncOutcome.SKIPPED, row.get().externalId(),
                    "È un agente di " + connector.name() + ": resta com'è.", List.of());
        }
        if (agent.getStatus() != AgentStatus.ACTIVE) {
            return new AgentSyncResult(agent.getId(), agent.getName(), connector.key(), AgentSyncOutcome.SKIPPED, null,
                    "L'agente è disattivato.", List.of());
        }
        Optional<String> why = connector.notApplicable(profile);
        if (why.isPresent()) {
            return new AgentSyncResult(agent.getId(), agent.getName(), connector.key(), AgentSyncOutcome.SKIPPED,
                    row.map(Publication::externalId).orElse(null), why.get(), List.of());
        }
        String print = connector.fingerprint(profile);
        if (row.isPresent() && print.equals(row.get().fingerprint()) && tool.has(row.get().externalId())) {
            return new AgentSyncResult(agent.getId(), agent.getName(), connector.key(), AgentSyncOutcome.UNCHANGED, row.get().externalId(),
                    "Già allineato.", List.of());
        }
        try {
            List<String> notes = new ArrayList<>();
            if (connector.key().equals(OpenClawConnector.KEY)) {
                notes.addAll(skillCopies(profile));
            }
            String externalId = row.map(Publication::externalId).orElseGet(() -> freeId(profile, connector.key()));
            AgentSyncConnector.Published published = connector.publish(profile, externalId);
            publications.record(agent.getId(), connector.key(), published.externalId(), published.fingerprint(), true, by);
            notes.addAll(published.notes());
            return new AgentSyncResult(agent.getId(), agent.getName(), connector.key(),
                    row.isPresent() && tool.has(row.get().externalId()) ? AgentSyncOutcome.UPDATED : AgentSyncOutcome.PUBLISHED,
                    published.externalId(), row.isPresent() ? "Aggiornato in " + connector.name() + "."
                    : "Pubblicato in " + connector.name() + ".", notes);
        } catch (RuntimeException e) {
            return new AgentSyncResult(agent.getId(), agent.getName(), connector.key(), AgentSyncOutcome.FAILED,
                    row.map(Publication::externalId).orElse(null), said(e), List.of());
        }
    }

    /**
     * The identifier a first publication takes: the agent's slug, or the slug and the
     * agent's id when another agent already stands under it (two names that differ only
     * by punctuation give one slug).
     */
    private String freeId(AgentProfile profile, String tool) {
        String slug = profile.slug();
        Optional<Long> holder = publications.agentOf(tool, slug);
        return holder.isEmpty() || holder.get().equals(profile.id()) ? slug : slug + "-" + profile.id();
    }

    /**
     * OpenClaw loads a skill by name from the folders it reads. A skill of the agent that
     * has no copy there is copied into the shared one -- never over a folder the Hub did
     * not write -- and what could not be copied is said.
     */
    private List<String> skillCopies(AgentProfile profile) {
        List<String> notes = new ArrayList<>();
        for (AgentProfile.Skill skill : profile.skills()) {
            try {
                if (hub.copyForTarget(skill.key(), OpenClawConnector.KEY).filter(SkillHubService.ToolCopy::usable).isPresent()) {
                    continue;
                }
                SkillHubService.DeployResult copy = hub.deploy(skill.key(), "agents", false);
                notes.add("Skill «" + skill.key() + "» copiata dove OpenClaw la legge (" + copy.path() + ").");
            } catch (RuntimeException e) {
                notes.add("Skill «" + skill.key() + "»: OpenClaw non ne ha una copia — copiala dal Knowledge Hub (" + said(e) + ").");
            }
        }
        return notes;
    }

    /** Takes an agent out of a tool: what was published is removed there, a brought-in agent is only unlinked. */
    public void unpublish(Long agentId, String toolKey) {
        AgentSyncConnector connector = connector(toolKey);
        if (!agents.existsById(agentId)) {
            throw new AgentNotFoundException(agentId);
        }
        Optional<Publication> row = publications.find(agentId, toolKey);
        if (row.isEmpty()) {
            return;
        }
        if (row.get().owned()) {
            connector.remove(row.get().externalId());
        }
        publications.forget(agentId, toolKey);
    }

    /**
     * Brings a tool's own agents into AI Company OS: each becomes an agent here that
     * works through that tool, linked to the one it came from, whose files and
     * configuration stay the tool's.
     *
     * @param externalIds the agents to bring in, or null for every one not linked yet
     */
    public List<ImportedAgent> importFrom(String toolKey, @Nullable List<String> externalIds, String by) {
        AgentSyncConnector connector = connector(toolKey);
        if (!connector.imports()) {
            throw SyncProblemException.refused(connector.name() + " non ha agenti suoi da portare in AI Company OS.");
        }
        Asked tool = ask(connector);
        if (!tool.status().available()) {
            throw SyncProblemException.unavailable(tool.status().detail());
        }
        List<ImportedAgent> out = new ArrayList<>();
        for (ExternalAgent external : tool.agents().values()) {
            if (external.ours() || (externalIds != null && !externalIds.isEmpty() && !externalIds.contains(external.externalId()))
                    || publications.agentOf(toolKey, external.externalId()).isPresent()) {
                continue;
            }
            Agent created = transactions.execute(tx -> {
                String name = freeName(external.name(), connector.name());
                Agent agent = new Agent(name, "Agente di " + connector.name(), specialization(external));
                agent.configureProfile(null, null, cut(external.instructions(), 8000), null, null, null, null, null);
                agent.configureBinding("Portato da " + connector.name() + " («" + external.externalId() + "»): lavora lì, con "
                        + "la sua identità, la sua memoria e i suoi file.", null, null, toolKey);
                agent.recordOrigin(AgentOrigin.USER, null);
                Agent saved = agents.saveAndFlush(agent);
                publications.record(saved.getId(), toolKey, external.externalId(), null, false, by);
                return saved;
            });
            out.add(new ImportedAgent(created.getId(), created.getName(), toolKey, external.externalId()));
        }
        return out;
    }

    /** The OpenClaw agent a task of this agent goes to: the one it was published as or brought in from. */
    public Optional<String> externalIdOf(Long agentId, String toolKey) {
        return publications.find(agentId, toolKey).map(Publication::externalId);
    }

    // --- helpers ------------------------------------------------------------------------

    private AgentSyncConnector connector(String key) {
        AgentSyncConnector connector = connectors.get(key);
        if (connector == null) {
            throw SyncProblemException.unknownTool(key);
        }
        return connector;
    }

    /** The agent as the tools are given it: its row, and its skills with what a run would be told of each. */
    AgentProfile profile(HarnessService.AgentHarness own) {
        List<AgentProfile.Skill> skills = new ArrayList<>();
        for (HarnessResource resource : own.resources()) {
            if (resource.getKind() != HarnessResource.Kind.SKILL) {
                continue;
            }
            String essential;
            try {
                essential = library.body(resource).flatMap(body -> Essentials.of(body, resource.getDescription(), essentialLimit))
                        .orElse(null);
            } catch (RuntimeException unreadable) {
                essential = null;
            }
            skills.add(new AgentProfile.Skill(resource.getKey(), resource.getName(), resource.getDescription(), essential));
        }
        return AgentProfile.of(own.agent(), skills);
    }

    private String freeName(String wanted, String toolName) {
        String name = cut(wanted, 200);
        if (!agents.existsByNormalisedName(name)) {
            return name;
        }
        String tagged = name + " (" + toolName + ")";
        for (int n = 2; agents.existsByNormalisedName(tagged); n++) {
            tagged = name + " (" + toolName + " " + n + ")";
        }
        return tagged;
    }

    /** What the tool's agent is for, in a line: the title of its operating program, or its description. */
    private static String specialization(ExternalAgent external) {
        if (external.instructions() != null) {
            for (String line : external.instructions().split("\\R")) {
                if (line.startsWith("# ") && line.length() > 2) {
                    return cut(line.substring(2).strip(), 255);
                }
            }
        }
        return cut(external.description() == null ? external.externalId() : external.description(), 255);
    }

    private static String cut(@Nullable String text, int max) {
        if (text == null) {
            return null;
        }
        String clean = text.strip();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private static String said(RuntimeException e) {
        if (e instanceof ProblemException problem && problem.getMessage() != null) {
            return problem.getMessage();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
