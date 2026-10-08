package com.aicompany.backend.orchestrator.service;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PHASE 32 (TD-49): a handoff to OpenCode, followed. The TUI the handoff opens
 * serves OpenCode's local API too ({@code --port}), on a port AI Company OS chose
 * and recorded with the handoff; this reads from it -- on 127.0.0.1 only -- what
 * the session there is doing: working or waiting, the files it changed, its last
 * answer. Read-only: nothing is ever sent to the session.
 */
@Component
public class OpenCodeSessions {

    public enum Status {
        /** The handoff did not open OpenCode with an API to follow. */
        NOT_FOLLOWED,
        /** Nothing answers on the port: OpenCode was closed, or not opened yet. */
        UNREACHABLE,
        /** OpenCode is open and no session started yet. */
        WAITING,
        BUSY,
        IDLE,
        /** The provider refused or failed and OpenCode is trying again. */
        RETRY
    }

    /** What the session is doing; {@code lastAnswer} the text of its latest answer, cut. */
    public record OpenCodeSession(Status status, @Nullable String sessionId, @Nullable String title, int files, int additions,
                              int deletions, @Nullable String lastAnswer, @Nullable Instant updatedAt) {

        static OpenCodeSession of(Status status) {
            return new OpenCodeSession(status, null, null, 0, 0, 0, null, null);
        }
    }

    static final int MAX_ANSWER = 4000;
    private static final Pattern SESSION_ID = Pattern.compile("ses[A-Za-z0-9_-]{1,120}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    /** A free port of this machine's loopback, for the TUI to serve its API on. */
    public static int freePort() {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public OpenCodeSession follow(@Nullable Integer port) {
        if (port == null || port < 1024 || port > 65535) {
            return OpenCodeSession.of(Status.NOT_FOLLOWED);
        }
        Optional<JsonNode> sessions = get(port, "/session");
        if (sessions.isEmpty() || !sessions.get().isArray()) {
            return OpenCodeSession.of(Status.UNREACHABLE);
        }
        JsonNode latest = null;
        for (JsonNode session : sessions.get()) {
            boolean root = session.path("parentID").isMissingNode() || session.path("parentID").isNull();
            if (root && SESSION_ID.matcher(session.path("id").asString("")).matches()
                    && (latest == null || updated(session) > updated(latest))) {
                latest = session;
            }
        }
        if (latest == null) {
            return OpenCodeSession.of(Status.WAITING);
        }
        String id = latest.path("id").asString();
        String type = get(port, "/session/status").map(s -> s.path(id).path("type").asString("idle")).orElse("idle");
        Status status = switch (type) {
            case "busy" -> Status.BUSY;
            case "retry" -> Status.RETRY;
            default -> Status.IDLE;
        };
        JsonNode summary = latest.path("summary");
        return new OpenCodeSession(status, id, latest.path("title").asString(null), summary.path("files").asInt(0),
                summary.path("additions").asInt(0), summary.path("deletions").asInt(0), lastAnswer(port, id),
                updated(latest) > 0 ? Instant.ofEpochMilli(updated(latest)) : null);
    }

    private @Nullable String lastAnswer(int port, String sessionId) {
        Optional<JsonNode> messages = get(port, "/session/" + sessionId + "/message?limit=20");
        if (messages.isEmpty() || !messages.get().isArray()) {
            return null;
        }
        String answer = null;
        for (JsonNode message : messages.get()) {
            if (!"assistant".equals(message.path("info").path("role").asString(""))) {
                continue;
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : message.path("parts")) {
                if ("text".equals(part.path("type").asString("")) && part.path("text").isString()) {
                    text.append(text.isEmpty() ? "" : "\n").append(part.path("text").asString());
                }
            }
            if (!text.isEmpty()) {
                answer = text.toString().strip();
            }
        }
        return answer == null || answer.length() <= MAX_ANSWER ? answer : answer.substring(0, MAX_ANSWER) + " …";
    }

    private static long updated(JsonNode session) {
        return session.path("time").path("updated").asLong(0);
    }

    /** A GET on OpenCode's API at 127.0.0.1; empty when nothing answers, or not with JSON. */
    private Optional<JsonNode> get(int port, String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                    .timeout(Duration.ofSeconds(3)).header("Accept", "application/json").GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() / 100 == 2 ? Optional.of(JSON.readTree(response.body())) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
