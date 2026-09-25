package com.aicompany.backend.terminal;

import com.aicompany.backend.user.service.SecurityLog;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A terminal in the browser (ADR-029): one WebSocket, one pseudo-terminal.
 *
 * <p>The protocol, all JSON text frames from the browser:
 * <ol>
 *   <li>{@code {"type":"auth","ticket":"…","cols":120,"rows":30}} -- first, within
 *       five seconds, or the socket is closed; the ticket is single-use;</li>
 *   <li>{@code {"type":"input","data":"…"}} -- keystrokes;</li>
 *   <li>{@code {"type":"resize","cols":…,"rows":…}}.</li>
 * </ol>
 * The terminal's output goes back as binary frames (raw bytes, which xterm.js
 * decodes), and its end as {@code {"type":"exit","code":n}} before the close.
 */
public class TerminalSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(TerminalSocketHandler.class);
    static final int MAX_SESSIONS = 4;
    private static final long AUTH_WINDOW_MILLIS = 5_000;

    private record Running(PtyProcess process, WebSocketSession socket) {
    }

    private final TerminalTickets tickets;
    private final SecurityLog log;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Map<String, Running> running = new ConcurrentHashMap<>();
    private final AtomicInteger open = new AtomicInteger();

    public TerminalSocketHandler(TerminalTickets tickets, SecurityLog log) {
        this.tickets = tickets;
        this.log = log;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(AUTH_WINDOW_MILLIS);
                if (session.isOpen() && !running.containsKey(session.getId())) {
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
        Running current = running.get(session.getId());
        if (current == null) {
            if (!"auth".equals(type)) {
                session.close(CloseStatus.POLICY_VIOLATION.withReason("authenticate first"));
                return;
            }
            start(session, frame);
            return;
        }
        switch (type) {
            case "input" -> {
                current.process().getOutputStream().write(frame.path("data").asString().getBytes(StandardCharsets.UTF_8));
                current.process().getOutputStream().flush();
            }
            case "resize" -> current.process().setWinSize(new WinSize(clamp(frame.path("cols").asInt(), 20, 500),
                    clamp(frame.path("rows").asInt(), 5, 200)));
            default -> {
                // unknown frames are ignored
            }
        }
    }

    private void start(WebSocketSession session, JsonNode auth) throws IOException {
        TerminalTickets.Ticket ticket = tickets.consume(auth.path("ticket").asString()).orElse(null);
        if (ticket == null) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid or expired ticket"));
            return;
        }
        if (open.incrementAndGet() > MAX_SESSIONS) {
            open.decrementAndGet();
            session.close(CloseStatus.SERVICE_OVERLOAD.withReason("too many terminals open"));
            return;
        }
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put("TERM", "xterm-256color");
        PtyProcess process;
        try {
            process = new PtyProcessBuilder(ticket.command().toArray(String[]::new))
                    .setDirectory(ticket.directory().toString())
                    .setEnvironment(environment)
                    .setInitialColumns(clamp(auth.path("cols").asInt(120), 20, 500))
                    .setInitialRows(clamp(auth.path("rows").asInt(30), 5, 200))
                    .setConsole(false)
                    .start();
        } catch (IOException | RuntimeException e) {
            open.decrementAndGet();
            session.sendMessage(new TextMessage("{\"type\":\"error\",\"message\":" + json.writeValueAsString(
                    "The terminal could not start: " + e.getMessage()) + "}"));
            session.close(CloseStatus.SERVER_ERROR);
            return;
        }
        WebSocketSession socket = new ConcurrentWebSocketSessionDecorator(session, 10_000, 4 * 1024 * 1024);
        running.put(session.getId(), new Running(process, socket));
        log.record(SecurityLog.PROCESS_STARTED, ticket.issuedTo(), null,
                "terminal " + ticket.shell() + " in " + ticket.directory());
        socket.sendMessage(new TextMessage("{\"type\":\"ready\",\"directory\":" + json.writeValueAsString(
                ticket.directory().toString()) + "}"));
        Thread.ofVirtual().name("terminal-" + session.getId()).start(() -> pump(session.getId(), process, socket));
    }

    private void pump(String id, PtyProcess process, WebSocketSession socket) {
        byte[] buffer = new byte[8192];
        try (InputStream out = process.getInputStream()) {
            int n;
            while ((n = out.read(buffer)) >= 0) {
                if (n > 0 && socket.isOpen()) {
                    socket.sendMessage(new BinaryMessage(java.util.Arrays.copyOf(buffer, n)));
                }
            }
        } catch (IOException ended) {
            // the process or the socket went away
        }
        try {
            int code = process.waitFor();
            if (socket.isOpen()) {
                socket.sendMessage(new TextMessage("{\"type\":\"exit\",\"code\":" + code + "}"));
                socket.close(CloseStatus.NORMAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException ignored) {
            // closed meanwhile
        } finally {
            if (running.remove(id) != null) {
                open.decrementAndGet();
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Running current = running.remove(session.getId());
        if (current != null) {
            open.decrementAndGet();
            current.process().destroyForcibly();
            LOG.debug("Terminal {} closed ({})", session.getId(), status);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
