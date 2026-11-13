package com.aicompany.backend.openclaw;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.harness.hub.HubCommands;
import com.aicompany.backend.harness.hub.HubCommands.Tool;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.sync.AgentPublications;
import com.aicompany.backend.sync.AgentSyncConnector.Availability;
import com.aicompany.backend.sync.AgentSyncConnector.Status;
import com.aicompany.backend.sync.OpenClawConnector;
import com.aicompany.backend.sync.SyncProblemException;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OpenClaw inside the console (ADR-045). OpenClaw's own Control UI refuses to be framed
 * ({@code X-Frame-Options: DENY}, {@code frame-ancestors 'none'}, not configurable): it
 * cannot sit in the console the way Open WebUI does. What the operator works with every
 * day is brought here instead, through {@code openclaw}'s own commands, as the
 * synchronisation already does (ADR-043): the gateway, the agents, the sessions, a
 * conversation with an agent, a session taken up again in a terminal -- and the full
 * Control UI one click away, opened by {@code openclaw dashboard}, which pairs the
 * browser itself. The gateway's token never passes through here.
 *
 * <p>An {@code openclaw} command takes half a minute or more on this machine (measured
 * 2026-11-12: 30-60 s, with the gateway up). Nothing the console asks waits for one: what
 * OpenClaw has is read in the background and kept, the page shows the last reading and
 * says when it was taken, and an agent's answer is collected while the console asks again.
 */
@Component
public class OpenClawConsole {

    /** How long a reading is shown before the next look starts another. */
    static final Duration FRESH = Duration.ofSeconds(30);
    static final int SESSIONS = 40;
    static final int MESSAGES_KEPT = 100;
    /** One agent turn: OpenClaw's own default, and a margin for the command to start and to answer. */
    static final int TURN_SECONDS = 600;
    private static final Duration TURN_TIMEOUT = Duration.ofSeconds(TURN_SECONDS + 60);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(120);
    /** The console's own conversation with each agent: one session, so that it goes on from message to message. */
    static final String CONSOLE_SESSION = "aicos-console";

    private static final Pattern AGENT_ID = Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");
    /** A session key as OpenClaw writes them ({@code agent:<id>:<key>}); nothing Windows Terminal reads as syntax. */
    static final Pattern SESSION_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:._@+-]{0,199}$");
    private static final Pattern TASK_SESSION = Pattern.compile(":aicos-task-(\\d+)$");
    private static final Pattern ANSI = Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");
    /** What follows a link's path -- where a one-time pairing would be -- is never repeated. */
    private static final Pattern LINK_TAIL = Pattern.compile("(https?://[^\\s#?]+)[#?]\\S*");

    // --- what the console is told -------------------------------------------------------

    public enum OpenClawControlUiState { IDLE, OPENING, OPENED, FAILED }

    public enum OpenClawMessageState { RUNNING, DONE, FAILED }

    /**
     * @param madeHere       the agent was published from AI Company OS ({@code aicos-} identifier)
     * @param aicosAgentId   the agent of AI Company OS it stands for, published or brought in; null when none
     */
    public record OpenClawAgent(String id, String name, boolean isDefault, boolean madeHere,
                                @Nullable Long aicosAgentId, @Nullable String aicosAgentName) {
    }

    /**
     * @param taskId  the task of AI Company OS the session was opened for by a handoff, if it was
     * @param console the console's own conversation with that agent
     */
    public record OpenClawSession(String key, String agentId, @Nullable String label, @Nullable String kind,
                                  @Nullable String model, @Nullable String provider, @Nullable Instant updatedAt,
                                  @Nullable Long totalTokens, @Nullable Long taskId, boolean console) {
    }

    public record OpenClawControlUi(OpenClawControlUiState state, @Nullable String detail, @Nullable Instant at) {
    }

    /**
     * @param refreshedAt when agents and sessions were last read; null before the first reading
     * @param refreshing  a reading is under way: ask again in a few seconds
     * @param problem     why the last reading failed, when it did (what is shown is the one before)
     */
    public record OpenClawOverview(Availability availability, String detail, @Nullable String hint, String gatewayUrl,
                                   List<OpenClawAgent> agents, List<OpenClawSession> sessions,
                                   @Nullable Instant refreshedAt, boolean refreshing, @Nullable String problem,
                                   OpenClawControlUi controlUi) {
    }

    public record OpenClawMessage(String id, String agentId, String sessionKey, String message, OpenClawMessageState state,
                                  @Nullable String reply, @Nullable String model, @Nullable String provider,
                                  @Nullable Long tokens, @Nullable String detail, Instant startedAt,
                                  @Nullable Instant finishedAt, String sentBy) {
    }

    public record OpenClawTerminal(String sessionKey, String command) {
    }

    /** The sessions as last read; the agents are the connector's own reading, shared with the synchronisation. */
    private record Snapshot(List<OpenClawSession> sessions, @Nullable Instant at, @Nullable String problem) {
    }

    private final OpenClawConnector connector;
    private final HubCommands commands;
    private final AgentPublications publications;
    private final AgentRepository aicosAgents;
    private final SoftwareService software;
    private final String gatewayUrl;
    private final ExecutorService background;
    /** Where the work of a reading, an answer, an opening runs: in the background, or -- in tests -- right away. */
    private final java.util.concurrent.Executor executor;

    private volatile @Nullable Snapshot snapshot;
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private volatile OpenClawControlUi controlUi = new OpenClawControlUi(OpenClawControlUiState.IDLE, null, null);
    private final Map<String, OpenClawMessage> messages = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, OpenClawMessage> eldest) {
            return size() > MESSAGES_KEPT;
        }
    };

    OpenClawConsole(OpenClawConnector connector, HubCommands commands, AgentPublications publications,
                    AgentRepository aicosAgents, SoftwareService software,
                    @Value("${aicos.sync.openclaw.gateway-url:http://127.0.0.1:18789}") String gatewayUrl,
                    @Value("${aicos.openclaw.background:true}") boolean background) {
        this.connector = connector;
        this.commands = commands;
        this.publications = publications;
        this.aicosAgents = aicosAgents;
        this.software = software;
        this.gatewayUrl = gatewayUrl == null ? "" : gatewayUrl.strip().replaceAll("/+$", "");
        this.background = background ? Executors.newVirtualThreadPerTaskExecutor() : null;
        this.executor = background ? this.background : Runnable::run;
    }

    @PreDestroy
    void stop() {
        if (background != null) {
            background.shutdownNow();
        }
    }

    // --- reading ------------------------------------------------------------------------

    /**
     * Where OpenClaw stands, with the last reading of its agents and sessions. Never waits
     * for {@code openclaw}: a reading older than {@link #FRESH}, or asked for, starts in the
     * background and the next look shows it. ADR-046: the agents are the connector's reading,
     * the same the synchronisation shows -- one command for both pages, not one each.
     */
    public OpenClawOverview overview(boolean refresh) {
        Status status = connector.status();
        if (!status.available()) {
            return new OpenClawOverview(status.availability(), status.detail(), status.hint(), gatewayUrl,
                    List.of(), List.of(), null, false, null, controlUi);
        }
        if (refresh) {
            connector.startReading();
        }
        OpenClawConnector.Known known = connector.known();
        Snapshot kept = snapshot;
        if (refresh || kept == null || kept.at() == null
                || kept.at().isBefore(Instant.now().minus(FRESH))) {
            startRefresh();
        }
        kept = snapshot;
        Instant sessionsAt = kept == null ? null : kept.at();
        Instant at = known.at() == null || sessionsAt == null ? null
                : known.at().isBefore(sessionsAt) ? known.at() : sessionsAt;
        String problem = kept != null && kept.problem() != null ? kept.problem() : known.problem();
        return new OpenClawOverview(status.availability(), status.detail(), null, gatewayUrl, agents(known),
                kept == null ? List.of() : kept.sessions(), at, refreshing.get() || known.reading(), problem, controlUi);
    }

    /** OpenClaw's agents, the default first, each tied to the agent of AI Company OS it stands for. */
    private List<OpenClawAgent> agents(OpenClawConnector.Known known) {
        if (known.agents().isEmpty()) {
            return List.of();
        }
        Map<String, Long> linked = publications.all().stream().filter(p -> p.tool().equals(OpenClawConnector.KEY))
                .collect(Collectors.toMap(AgentPublications.Publication::externalId,
                        AgentPublications.Publication::agentId, (a, b) -> a));
        Map<Long, String> names = aicosAgents.findAllById(linked.values()).stream()
                .collect(Collectors.toMap(Agent::getId, Agent::getName));
        return known.agents().stream().map(a -> {
            Long aicos = linked.get(a.externalId());
            return new OpenClawAgent(a.externalId(), a.name(), a.externalId().equals(known.defaultAgent()), a.ours(),
                    aicos, aicos == null ? null : names.get(aicos));
        }).sorted(Comparator.comparing((OpenClawAgent a) -> !a.isDefault()).thenComparing(OpenClawAgent::id)).toList();
    }

    private void startRefresh() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::refresh);
        } catch (RuntimeException rejected) {
            refreshing.set(false);
        }
    }

    private void refresh() {
        try {
            snapshot = new Snapshot(readSessions(), Instant.now(), null);
        } catch (RuntimeException failed) {
            Snapshot before = snapshot;
            String why = failed.getMessage();
            snapshot = new Snapshot(before == null ? List.of() : before.sessions(), before == null ? null : before.at(),
                    why == null ? failed.getClass().getSimpleName() : why);
        } finally {
            refreshing.set(false);
        }
    }

    /** {@code openclaw sessions --all-agents --json}: the newest sessions of every agent. */
    private List<OpenClawSession> readSessions() {
        HubCommands.Result result = commands.run(Tool.OPENCLAW,
                List.of("sessions", "--all-agents", "--json", "--limit", String.valueOf(SESSIONS)), READ_TIMEOUT);
        if (!result.ok()) {
            throw SyncProblemException.refused("OpenClaw non ha elencato le sue sessioni"
                    + (result.timedOut() ? ": nessuna risposta entro " + READ_TIMEOUT.toSeconds() + " secondi." : ": " + tail(result.output())));
        }
        JsonNode rows = connector.firstJson(result.output()).map(n -> n.path("sessions")).orElse(null);
        List<OpenClawSession> out = new ArrayList<>();
        if (rows == null || !rows.isArray()) {
            return out;
        }
        for (JsonNode row : rows) {
            String key = row.path("key").asString("");
            if (key.isBlank() || !SESSION_KEY.matcher(key).matches()) {
                continue;
            }
            String agent = row.path("agentId").asString("");
            Matcher task = TASK_SESSION.matcher(key);
            out.add(new OpenClawSession(key, agent, text(row, "label"), text(row, "kind"), text(row, "model"),
                    text(row, "modelProvider"), row.path("updatedAt").isNumber()
                    ? Instant.ofEpochMilli(row.path("updatedAt").asLong()) : null,
                    row.path("totalTokens").isNumber() ? row.path("totalTokens").asLong() : null,
                    task.find() ? Long.valueOf(task.group(1)) : null, key.equals(consoleSession(agent))));
        }
        return out;
    }

    // --- the full Control UI ------------------------------------------------------------

    /**
     * Opens OpenClaw's Control UI in the operator's browser with {@code openclaw dashboard}:
     * OpenClaw issues a one-time pairing for that browser and opens it itself, so neither
     * its token nor the pairing goes through the control plane. The outcome is in the
     * overview a few seconds later.
     */
    public OpenClawControlUi openControlUi() {
        requireAvailable();
        if (controlUi.state() == OpenClawControlUiState.OPENING) {
            return controlUi;
        }
        controlUi = new OpenClawControlUi(OpenClawControlUiState.OPENING, "Chiedo a OpenClaw di aprire la Control UI nel browser…",
                Instant.now());
        executor.execute(() -> {
            HubCommands.Result result = commands.run(Tool.OPENCLAW, List.of("dashboard"), READ_TIMEOUT);
            String output = clean(result.output());
            controlUi = result.ok() && output.contains("Opened in your browser")
                    ? new OpenClawControlUi(OpenClawControlUiState.OPENED,
                    "Aperta nel browser predefinito, già collegata al gateway: tieni quella scheda.", Instant.now())
                    : new OpenClawControlUi(OpenClawControlUiState.FAILED, "OpenClaw non ha aperto il browser"
                    + (output.isBlank() ? "." : ": " + tail(output)) + " Prova «openclaw dashboard» in un terminale.", Instant.now());
        });
        return controlUi;
    }

    // --- a session in the terminal ------------------------------------------------------

    /** Opens {@code openclaw tui} on one session in Windows Terminal: the conversation goes on there, whole. */
    public OpenClawTerminal openInTerminal(String sessionKey) {
        requireAvailable();
        if (!SESSION_KEY.matcher(sessionKey).matches()) {
            throw SyncProblemException.refused("'" + sessionKey + "' is not an OpenClaw session key");
        }
        LaunchPlan plan = software.launchIn(OpenClawConnector.KEY, null, List.of("--session", sessionKey));
        return new OpenClawTerminal(sessionKey, String.join(" ", plan.command()));
    }

    // --- talking to an agent ------------------------------------------------------------

    /**
     * Sends one message to an OpenClaw agent, in the console's own session with it, and
     * returns at once: the answer is collected in the background ({@link #message}). One
     * message at a time per agent, as OpenClaw itself runs one turn at a time per session.
     */
    public OpenClawMessage send(String agentId, String text, String by) {
        requireAvailable();
        if (!AGENT_ID.matcher(agentId).matches()) {
            throw SyncProblemException.refused("'" + agentId + "' is not an OpenClaw agent id");
        }
        OpenClawConnector.Known known = connector.known();
        if (!known.known()) {
            throw SyncProblemException.unavailable("Sto ancora leggendo gli agenti di OpenClaw: riprova fra qualche secondo.");
        }
        if (known.agents().stream().noneMatch(a -> a.externalId().equals(agentId))) {
            throw notFound("OpenClaw has no agent '" + agentId + "'");
        }
        OpenClawMessage sent;
        synchronized (messages) {
            if (messages.values().stream().anyMatch(m -> m.agentId().equals(agentId) && m.state() == OpenClawMessageState.RUNNING)) {
                throw SyncProblemException.refused("«" + agentId + "» sta ancora rispondendo al messaggio di prima: aspetta la risposta.");
            }
            sent = new OpenClawMessage(UUID.randomUUID().toString(), agentId, consoleSession(agentId), text.strip(),
                    OpenClawMessageState.RUNNING, null, null, null, null, null, Instant.now(), null, by);
            messages.put(sent.id(), sent);
        }
        OpenClawMessage running = sent;
        executor.execute(() -> finish(running));
        return message(sent.id());
    }

    /** One message the console sent, with the answer once it came. */
    public OpenClawMessage message(String id) {
        synchronized (messages) {
            OpenClawMessage found = messages.get(id);
            if (found == null) {
                throw notFound("No message '" + id + "' was sent from this console (they are kept until the backend restarts)");
            }
            return found;
        }
    }

    /** The messages kept, oldest first: the console's conversations since the backend started. */
    public List<OpenClawMessage> messages() {
        synchronized (messages) {
            return List.copyOf(messages.values());
        }
    }

    private void finish(OpenClawMessage sent) {
        Path file = null;
        OpenClawMessage done;
        try {
            // The message goes as a file: a command line cannot carry every character an operator types.
            file = Files.createTempFile("aicos-openclaw-message-", ".txt");
            Files.writeString(file, sent.message(), StandardCharsets.UTF_8);
            HubCommands.Result result = commands.run(Tool.OPENCLAW, List.of("agent", "--agent", sent.agentId(),
                    "--session-key", sent.sessionKey(), "--message-file", file.toString(), "--json",
                    "--timeout", String.valueOf(TURN_SECONDS)), TURN_TIMEOUT);
            done = answered(sent, result);
        } catch (IOException | RuntimeException e) {
            done = failed(sent, "Il messaggio non è partito: " + e.getMessage());
        } finally {
            if (file != null) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    // a temporary file the system clears
                }
            }
        }
        synchronized (messages) {
            messages.put(sent.id(), done);
        }
        // The conversation changed the session list: the next look reads it again.
        Snapshot kept = snapshot;
        if (kept != null) {
            snapshot = new Snapshot(kept.sessions(), Instant.EPOCH, kept.problem());
        }
    }

    /**
     * Reads {@code openclaw agent --json} as the gateway answers it: {@code status}, and in
     * {@code result} the reply's {@code payloads} and the run's {@code meta.agentMeta}.
     */
    OpenClawMessage answered(OpenClawMessage sent, HubCommands.Result result) {
        if (result.timedOut()) {
            return failed(sent, "Nessuna risposta entro " + TURN_TIMEOUT.toSeconds() + " secondi. La sessione continua in "
                    + "OpenClaw: aprila nel terminale per vedere a che punto è.");
        }
        Optional<JsonNode> json = connector.firstJson(result.output());
        if (json.isEmpty() || !json.get().isObject()) {
            return failed(sent, "OpenClaw non ha risposto" + (result.output() == null || result.output().isBlank() ? "."
                    : ": " + tail(result.output())));
        }
        JsonNode answer = json.get();
        String status = answer.path("status").asString("");
        JsonNode payloads = answer.path("result").path("payloads");
        List<String> texts = new ArrayList<>();
        if (payloads.isArray()) {
            for (JsonNode payload : payloads) {
                String text = payload.path("text").asString("");
                if (!text.isBlank()) {
                    texts.add(text.strip());
                }
            }
        }
        JsonNode meta = answer.path("result").path("meta").path("agentMeta");
        String model = text(meta, "model");
        String provider = text(meta, "provider");
        Long tokens = meta.path("usage").path("total").isNumber() ? meta.path("usage").path("total").asLong() : null;
        boolean ok = result.ok() && (status.equals("ok") || status.equals("completed"));
        if (!ok) {
            String why = firstText(answer.path("error").path("message"), answer.path("summary"));
            return new OpenClawMessage(sent.id(), sent.agentId(), sent.sessionKey(), sent.message(), OpenClawMessageState.FAILED,
                    texts.isEmpty() ? null : String.join("\n\n", texts), model, provider, tokens,
                    "OpenClaw: " + (why == null ? (status.isBlank() ? "il turno non è finito" : status) : why),
                    sent.startedAt(), Instant.now(), sent.sentBy());
        }
        String reply = texts.isEmpty() ? null : String.join("\n\n", texts);
        return new OpenClawMessage(sent.id(), sent.agentId(), sent.sessionKey(), sent.message(), OpenClawMessageState.DONE,
                reply, model, provider, tokens, reply == null ? "L'agente non ha scritto niente in risposta." : null,
                sent.startedAt(), Instant.now(), sent.sentBy());
    }

    private static OpenClawMessage failed(OpenClawMessage sent, String why) {
        return new OpenClawMessage(sent.id(), sent.agentId(), sent.sessionKey(), sent.message(), OpenClawMessageState.FAILED,
                null, null, null, null, why, sent.startedAt(), Instant.now(), sent.sentBy());
    }

    // --- helpers ------------------------------------------------------------------------

    static String consoleSession(String agentId) {
        return "agent:" + agentId + ":" + CONSOLE_SESSION;
    }

    private void requireAvailable() {
        Status status = connector.status();
        if (!status.available()) {
            throw SyncProblemException.unavailable(status.detail() + (status.hint() == null ? "" : " " + status.hint()));
        }
    }

    private static OpenClawProblemException notFound(String detail) {
        return new OpenClawProblemException(detail);
    }

    private static @Nullable String text(JsonNode node, String field) {
        String value = node.path(field).asString("");
        return value.isBlank() ? null : value;
    }

    private static @Nullable String firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            String value = node.asString("");
            if (!value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private static String clean(@Nullable String output) {
        return ANSI.matcher(output == null ? "" : output).replaceAll("").strip();
    }

    /** The end of a command's output, without colours and without what follows a link's path. */
    static String tail(@Nullable String output) {
        String text = LINK_TAIL.matcher(clean(output)).replaceAll("$1");
        return text.length() <= 600 ? text : "…" + text.substring(text.length() - 600);
    }

    /** For tests: what was read is let go, as after a restart. */
    void forget() {
        snapshot = null;
        controlUi = new OpenClawControlUi(OpenClawControlUiState.IDLE, null, null);
        synchronized (messages) {
            messages.clear();
        }
    }
}
