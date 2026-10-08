package com.aicompany.backend.terminal;

import com.aicompany.backend.user.service.SecurityLog;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import com.pty4j.windows.conpty.WinConPtyProcess;
import com.pty4j.windows.winpty.WinPtyProcess;
import jakarta.annotation.PreDestroy;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Terminals in the browser (ADR-029): pseudo-terminals shown over WebSockets.
 *
 * <p>The protocol, all JSON text frames from the browser:
 * <ol>
 *   <li>{@code {"type":"auth","ticket":"…","cols":120,"rows":30}} -- first, within
 *       five seconds, or the socket is closed; the ticket is single-use, and either
 *       starts a shell or attaches to one of the same person's;</li>
 *   <li>{@code {"type":"input","data":"…"}} -- keystrokes;</li>
 *   <li>{@code {"type":"resize","cols":…,"rows":…}};</li>
 *   <li>{@code {"type":"close"}} -- the console closed the shell: its process ends.</li>
 * </ol>
 * The answer starts with {@code {"type":"ready","session":…,"directory":…,"pty":…}};
 * the output goes back as binary frames (raw bytes, which xterm.js decodes), and
 * the end as {@code {"type":"exit","code":n}} before the close.
 *
 * <p>A shell outlives its socket (§5): a socket that goes away without a
 * {@code close} -- a reloaded page, a closed browser tab, a dropped connection --
 * leaves it running, detached, with its latest output kept. Attaching again sends
 * that output and the terminal modes the program had set, then asks the program
 * to repaint. A shell left detached longer than {@code aicos.terminal.detached-ttl}
 * is ended.
 */
@Component
public class TerminalSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalSocketHandler.class);
    static final int MAX_SESSIONS = 4;
    private static final long AUTH_WINDOW_MILLIS = 5_000;
    /** The shell was attached from another socket: this one is let go. */
    static final CloseStatus TAKEN_OVER = new CloseStatus(4001, "attached elsewhere");
    /** No such shell for this person: it ended, or it never was theirs. */
    static final CloseStatus GONE = new CloseStatus(4004, "terminal ended");

    public record TerminalSession(String id, String shell, String directory, Instant startedAt, boolean attached,
                            @Nullable Instant detachedAt) {
    }

    private final TerminalTickets tickets;
    private final SecurityLog log;
    private final Duration detachedTtl;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, RunningShell> shells = new ConcurrentHashMap<>();
    private final Map<String, RunningShell> bySocket = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();
    private final SecureRandom random = new SecureRandom();
    private final ScheduledExecutorService reaper;

    public TerminalSocketHandler(TerminalTickets tickets, SecurityLog log,
                                 @Value("${aicos.terminal.detached-ttl:PT24H}") Duration detachedTtl) {
        this.tickets = tickets;
        this.log = log;
        this.detachedTtl = detachedTtl;
        this.reaper = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("terminal-reaper").factory());
        long every = Math.clamp(detachedTtl.toMillis() / 2, 200, 60_000);
        reaper.scheduleWithFixedDelay(this::reapDetached, every, every, TimeUnit.MILLISECONDS);
    }

    // --- what the API asks ---------------------------------------------------

    /** The shells of one person, oldest first: shown on a socket or detached. */
    public List<TerminalSession> list(String owner) {
        return shells.values().stream().filter(s -> s.owner.equals(owner)).map(RunningShell::view)
                .sorted(Comparator.comparing(TerminalSession::startedAt)).toList();
    }

    public Optional<TerminalSession> find(String id, String owner) {
        return Optional.ofNullable(shells.get(id)).filter(s -> s.owner.equals(owner)).map(RunningShell::view);
    }

    /** Ends one of this person's shells; false when there is none with that id. */
    public boolean close(String id, String owner) {
        RunningShell shell = shells.get(id);
        if (shell == null || !shell.owner.equals(owner)) {
            return false;
        }
        shell.closeRequested = true;
        shell.process.destroyForcibly();
        return true;
    }

    // --- the socket ----------------------------------------------------------

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(AUTH_WINDOW_MILLIS);
                if (session.isOpen() && !bySocket.containsKey(session.getId())) {
                    session.close(CloseStatus.POLICY_VIOLATION.withReason("no ticket"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
                // already closed
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonNode frame = json.readTree(message.getPayload());
        String type = frame.path("type").asString();
        RunningShell current = bySocket.get(session.getId());
        if (current == null) {
            if (!"auth".equals(type)) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("authenticate first"));
                return;
            }
            authenticate(session, frame);
            return;
        }
        switch (type) {
            case "input" -> {
                current.process.getOutputStream().write(frame.path("data").asString().getBytes(StandardCharsets.UTF_8));
                current.process.getOutputStream().flush();
            }
            case "resize" -> current.process.setWinSize(new WinSize(clamp(frame.path("cols").asInt(), 20, 500),
                    clamp(frame.path("rows").asInt(), 5, 200)));
            case "close" -> {
                current.closeRequested = true;
                current.process.destroyForcibly();
            }
            default -> {
                // unknown frames are ignored
            }
        }
    }

    private void authenticate(WebSocketSession session, JsonNode auth) throws IOException {
        TerminalTickets.Ticket ticket = tickets.consume(auth.path("ticket").asString()).orElse(null);
        if (ticket == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid or expired ticket"));
            return;
        }
        int cols = clamp(auth.path("cols").asInt(120), 20, 500);
        int rows = clamp(auth.path("rows").asInt(30), 5, 200);
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(session, 10_000, 4 * 1024 * 1024);
        if (ticket.attach() != null) {
            attach(session, socket, ticket, cols, rows);
        } else {
            start(session, socket, ticket, cols, rows);
        }
    }

    private void start(WebSocketSession session, WebSocketSession socket, TerminalTickets.Ticket ticket,
                       int cols, int rows) throws IOException {
        if (open.incrementAndGet() > MAX_SESSIONS) {
            open.decrementAndGet();
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("too many terminals open"));
            return;
        }
        PtyProcess process;
        try {
            process = new PtyProcessBuilder(ticket.command().toArray(String[]::new))
                    .setDirectory(ticket.directory().toString())
                    .setEnvironment(ShellEnvironment.forShells(System.getenv()))
                    .setInitialColumns(cols)
                    .setInitialRows(rows)
                    .setConsole(false)
                    // ConPTY (pty4j's bundled one, from Windows Terminal), not winpty: winpty
                    // scrapes the console and repaints it in place, so what scrolls off the top
                    // never reaches xterm.js's scrollback and mouse modes do not pass through.
                    .setUseWinConPty(true)
                    .start();
        } catch (IOException | RuntimeException e) {
            open.decrementAndGet();
            socket.sendMessage(frame(Map.of("type", "error", "message", "The terminal could not start: " + e.getMessage())));
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        RunningShell shell = new RunningShell(newId(), ticket.issuedTo(), ticket.shell(), ticket.directory(),
                process, kind(process));
        shells.put(shell.id, shell);
        synchronized (shell) {
            shell.socket = socket;
            shell.socketId = session.getId();
            bySocket.put(session.getId(), shell);
            socket.sendMessage(ready(shell, false));
        }
        log.record(SecurityLog.PROCESS_STARTED, ticket.issuedTo(), null,
                "terminal " + ticket.shell() + " in " + ticket.directory());
        Thread.ofVirtual().name("terminal-" + shell.id).start(() -> pump(shell));
    }

    private void attach(WebSocketSession session, WebSocketSession socket, TerminalTickets.Ticket ticket,
                        int cols, int rows) throws IOException {
        RunningShell shell = shells.get(ticket.attach());
        if (shell == null || !shell.owner.equals(ticket.issuedTo())) {
            socket.sendMessage(frame(Map.of("type", "error", "message", "This terminal has ended.")));
            session.close(GONE);
            return;
        }
        WebSocketSession previous;
        synchronized (shell) {
            previous = shell.socket;
            if (shell.socketId != null) {
                bySocket.remove(shell.socketId);
            }
            shell.socket = socket;
            shell.socketId = session.getId();
            shell.detachedAt = null;
            bySocket.put(session.getId(), shell);
            socket.sendMessage(ready(shell, true));
            // A full-screen program repaints the whole screen; what it drew before is not
            // replayed into the normal buffer. A plain shell gets its history back.
            byte[] replay = shell.modes.inAlternateScreen() ? new byte[0] : shell.history.replay();
            byte[] modes = shell.modes.restore();
            byte[] out = new byte[replay.length + modes.length];
            System.arraycopy(replay, 0, out, 0, replay.length);
            System.arraycopy(modes, 0, out, replay.length, modes.length);
            if (out.length > 0) {
                socket.sendMessage(new BinaryMessage(out));
            }
        }
        if (previous != null && previous.isOpen()) {
            try {
                previous.close(TAKEN_OVER);
            } catch (IOException ignored) {
                // it was going anyway
            }
        }
        repaint(shell, cols, rows);
    }

    /**
     * A resize is what makes ConPTY and full-screen programs draw the screen again:
     * one row less, then the console's size.
     */
    private void repaint(RunningShell shell, int cols, int rows) {
        Thread.ofVirtual().start(() -> {
            try {
                shell.process.setWinSize(new WinSize(cols, Math.max(5, rows - 1)));
                Thread.sleep(80);
                shell.process.setWinSize(new WinSize(cols, rows));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException ended) {
                // the process went away meanwhile
            }
        });
    }

    private void pump(RunningShell shell) {
        byte[] buffer = new byte[8192];
        try (InputStream out = shell.process.getInputStream()) {
            int n;
            while ((n = out.read(buffer)) >= 0) {
                if (n == 0) {
                    continue;
                }
                byte[] chunk = Arrays.copyOf(buffer, n);
                synchronized (shell) {
                    shell.history.append(chunk, n);
                    shell.modes.scan(chunk, n);
                    deliver(shell, new BinaryMessage(chunk));
                }
            }
        } catch (IOException ended) {
            // the process went away
        }
        int code;
        try {
            code = shell.process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        synchronized (shell) {
            deliver(shell, frame(Map.of("type", "exit", "code", code)));
            if (shell.socket != null && shell.socket.isOpen()) {
                try {
                    shell.socket.close(CloseStatus.NORMAL);
                } catch (IOException ignored) {
                    // closed meanwhile
                }
            }
            if (shell.socketId != null) {
                bySocket.remove(shell.socketId);
            }
        }
        if (shells.remove(shell.id) != null) {
            open.decrementAndGet();
        }
    }

    /** To the socket it is shown on, if any; a socket that fails is detached when it closes. */
    private static void deliver(RunningShell shell, WebSocketMessage<?> message) {
        WebSocketSession socket = shell.socket;
        if (socket == null || !socket.isOpen()) {
            return;
        }
        try {
            socket.sendMessage(message);
        } catch (IOException | RuntimeException gone) {
            // the socket is going away: afterConnectionClosed detaches the shell
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        RunningShell shell = bySocket.remove(session.getId());
        if (shell == null) {
            return;
        }
        if (shell.closeRequested) {
            shell.process.destroyForcibly();
            return;
        }
        synchronized (shell) {
            if (session.getId().equals(shell.socketId)) {
                shell.socket = null;
                shell.socketId = null;
                shell.detachedAt = Instant.now();
                LOG.debug("Terminal {} detached ({})", shell.id, status);
            }
        }
    }

    private void reapDetached() {
        Instant limit = Instant.now().minus(detachedTtl);
        for (RunningShell shell : shells.values()) {
            Instant detachedAt;
            synchronized (shell) {
                detachedAt = shell.detachedAt;
            }
            if (detachedAt != null && detachedAt.isBefore(limit)) {
                LOG.info("Terminal {} ({}) detached since {}: ended", shell.id, shell.shell, detachedAt);
                shell.closeRequested = true;
                shell.process.destroyForcibly();
            }
        }
    }

    /** No shell outlives the control plane. */
    @PreDestroy
    void shutdown() {
        reaper.shutdownNow();
        shells.values().forEach(shell -> shell.process.destroyForcibly());
    }

    // --- helpers -------------------------------------------------------------

    private TextMessage ready(RunningShell shell, boolean attached) {
        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "ready");
        ready.put("session", shell.id);
        ready.put("shell", shell.shell);
        ready.put("directory", shell.directory.toString());
        ready.put("pty", shell.pty);
        ready.put("attached", attached);
        return frame(ready);
    }

    private TextMessage frame(Map<String, ?> content) {
        return new TextMessage(json.writeValueAsString(content));
    }

    private String newId() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Which pseudo-terminal runs the shell: xterm.js adapts to ConPTY's line handling. */
    static String kind(PtyProcess process) {
        if (process instanceof WinConPtyProcess) {
            return "conpty";
        }
        return process instanceof WinPtyProcess ? "winpty" : "unix";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
