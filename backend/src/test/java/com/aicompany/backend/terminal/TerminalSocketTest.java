package com.aicompany.backend.terminal;

import com.aicompany.backend.support.AdminSession;
import com.aicompany.backend.support.FakeHostConfiguration;
import com.aicompany.backend.support.PostgresTestcontainerConfig;
import com.aicompany.backend.support.ScriptedEngineConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 22 (ADR-029): a real pseudo-terminal behind a real WebSocket. The shell
 * is a harmless test command, enabled only by a test property: it prints a line
 * and then echoes what it is sent until it is told to stop -- {@code cmd /k} on
 * Windows, {@code cat} elsewhere.
 *
 * <p>§5: the shell outlives its socket, is attached again with what it printed
 * meanwhile, and ends when the console closes it, when its owner closes it from
 * the API, or when it has been alone longer than the detached time to live
 * (six seconds here).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestcontainerConfig.class, ScriptedEngineConfiguration.class, FakeHostConfiguration.class})
class TerminalSocketTest {

    private static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    private static final Pattern SESSION = Pattern.compile("\"session\":\"([^\"]+)\"");

    @DynamicPropertySource
    static void aHarmlessShell(DynamicPropertyRegistry registry) {
        registry.add("aicos.terminal.test-command", () -> WINDOWS
                ? "cmd.exe,/q,/k,echo hello-terminal" : "/bin/sh,-c,echo hello-terminal; exec cat");
        registry.add("aicos.terminal.detached-ttl", () -> "PT6S");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private String send(String method, String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (!bearer.isEmpty()) {
            request.header("Authorization", bearer);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body();
    }

    private String adminBearer() throws Exception {
        String answer = send("POST", "/api/auth/login", "", "{\"username\":\"admin\",\"password\":\"" + AdminSession.PASSWORD + "\"}");
        return "Bearer " + json.readTree(answer.substring(answer.indexOf(' ') + 1)).path("token").asString();
    }

    private String ticket(String bearer, String body) throws Exception {
        String answer = send("POST", "/api/terminal/tickets", bearer, body);
        assertThat(answer).startsWith("200 ");
        return json.readTree(answer.substring(4)).path("ticket").asString();
    }

    private JsonNode sessions(String bearer) throws Exception {
        String answer = send("GET", "/api/terminal/sessions", bearer, null);
        assertThat(answer).startsWith("200 ");
        return json.readTree(answer.substring(4));
    }

    /** Collects everything a socket receives until it closes. */
    static final class Recorder extends AbstractWebSocketHandler {
        final StringBuffer text = new StringBuffer();
        final StringBuffer output = new StringBuffer();
        final CompletableFuture<CloseStatus> closed = new CompletableFuture<>();

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            text.append(message.getPayload()).append('\n');
        }

        @Override
        protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
            byte[] bytes = new byte[message.getPayload().remaining()];
            message.getPayload().get(bytes);
            output.append(new String(bytes, StandardCharsets.UTF_8));
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            closed.complete(status);
        }

        String session() {
            Matcher matcher = SESSION.matcher(text);
            return matcher.find() ? matcher.group(1) : null;
        }
    }

    private WebSocketSession connect(Recorder recorder) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://console.test");
        return new StandardWebSocketClient().execute(recorder, headers,
                URI.create("ws://127.0.0.1:" + port + TerminalConfiguration.SOCKET_PATH)).get(10, TimeUnit.SECONDS);
    }

    private WebSocketSession open(Recorder recorder, String ticket) throws Exception {
        WebSocketSession socket = connect(recorder);
        socket.sendMessage(new TextMessage("{\"type\":\"auth\",\"ticket\":\"" + ticket + "\",\"cols\":100,\"rows\":20}"));
        return socket;
    }

    private static void input(WebSocketSession socket, String data) throws Exception {
        socket.sendMessage(new TextMessage("{\"type\":\"input\",\"data\":" + JsonMapper.builder().build().writeValueAsString(data) + "}"));
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        Instant limit = Instant.now().plus(Duration.ofSeconds(20));
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waiting for " + what).isBefore(limit);
            Thread.sleep(50);
        }
    }

    /** Stops the test shell from the inside: {@code exit} for cmd, end of input for cat. */
    private static final String EXIT = WINDOWS ? "exit\r" : "\u0004";

    @Test
    void anAdminsTicketOpensAPseudoTerminalWhoseOutputStreamsBack() throws Exception {
        String ticket = ticket(adminBearer(), "{\"shell\":\"test\"}");
        Recorder recorder = new Recorder();
        WebSocketSession socket = open(recorder, ticket);

        await("the shell's first line", () -> recorder.output.toString().contains("hello-terminal"));
        input(socket, EXIT);
        CloseStatus status = recorder.closed.get(30, TimeUnit.SECONDS);
        assertThat(recorder.text.toString()).contains("\"type\":\"ready\"").contains("\"type\":\"exit\"");
        assertThat(status.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());
        // On Windows it is ConPTY, which passes mouse modes and real scrolling through to
        // xterm.js; winpty repainted in place, and Claude Code's chat could not be scrolled.
        assertThat(recorder.text.toString()).contains(WINDOWS ? "\"pty\":\"conpty\"" : "\"pty\":\"unix\"");

        // The ticket was single-use.
        Recorder again = new Recorder();
        open(again, ticket);
        assertThat(again.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(again.output.toString()).isEmpty();
    }

    @Test
    void aShellOutlivesItsSocketAndIsShownAgainWithWhatItPrinted() throws Exception {
        String bearer = adminBearer();
        Recorder first = new Recorder();
        WebSocketSession socket = open(first, ticket(bearer, "{\"shell\":\"test\"}"));
        await("the shell's first line", () -> first.output.toString().contains("hello-terminal"));
        input(socket, "echo marker-one\r");
        await("the first marker", () -> first.output.toString().contains("marker-one"));
        String session = first.session();
        assertThat(session).isNotBlank();

        // The page is reloaded: the socket goes, without a "close".
        socket.close(CloseStatus.GOING_AWAY);
        await("the shell to be detached", () -> {
            try {
                JsonNode list = sessions(bearer);
                return list.size() == 1 && !list.get(0).path("attached").asBoolean();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(sessions(bearer).get(0).path("id").asString()).isEqualTo(session);

        // Attached again: the same shell, what it printed, and it still answers.
        Recorder second = new Recorder();
        WebSocketSession again = open(second, ticket(bearer, "{\"attach\":\"" + session + "\"}"));
        await("the replay", () -> second.output.toString().contains("marker-one"));
        assertThat(second.text.toString()).contains("\"attached\":true").contains("\"session\":\"" + session + "\"");
        input(again, "echo marker-two\r");
        await("the second marker", () -> second.output.toString().contains("marker-two"));

        // Attached from a third socket (another browser tab): the second is let go.
        Recorder third = new Recorder();
        WebSocketSession last = open(third, ticket(bearer, "{\"attach\":\"" + session + "\"}"));
        assertThat(second.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(TerminalSocketHandler.TAKEN_OVER.getCode());
        await("the replay on the third socket", () -> third.output.toString().contains("marker-two"));

        // Closed from the console: the process ends, and nothing is left to attach.
        last.sendMessage(new TextMessage("{\"type\":\"close\"}"));
        third.closed.get(30, TimeUnit.SECONDS);
        await("the shell to be gone", () -> {
            try {
                return sessions(bearer).isEmpty();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(send("POST", "/api/terminal/tickets", bearer, "{\"attach\":\"" + session + "\"}"))
                .startsWith("404 ").contains("terminal-not-found");
    }

    @Test
    void aShellLeftAloneLongerThanItsTimeToLiveIsEnded() throws Exception {
        String bearer = adminBearer();
        Recorder recorder = new Recorder();
        WebSocketSession socket = open(recorder, ticket(bearer, "{\"shell\":\"test\"}"));
        await("the shell's first line", () -> recorder.output.toString().contains("hello-terminal"));
        String session = recorder.session();

        socket.close(CloseStatus.GOING_AWAY);
        await("the shell to be ended after six seconds alone", () -> {
            try {
                for (JsonNode shell : sessions(bearer)) {
                    if (shell.path("id").asString().equals(session)) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    void itsOwnerClosesADetachedShellFromTheApi() throws Exception {
        String bearer = adminBearer();
        Recorder recorder = new Recorder();
        WebSocketSession socket = open(recorder, ticket(bearer, "{\"shell\":\"test\"}"));
        await("the shell's first line", () -> recorder.output.toString().contains("hello-terminal"));
        String session = recorder.session();
        socket.close(CloseStatus.GOING_AWAY);

        assertThat(send("DELETE", "/api/terminal/sessions/" + session, bearer, null)).startsWith("204 ");
        await("the shell to be gone", () -> {
            try {
                return sessions(bearer).isEmpty();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(send("DELETE", "/api/terminal/sessions/" + session, bearer, null)).startsWith("404 ");
    }

    @Test
    void anotherAdminNeitherSeesNorTakesNorClosesYourShells() throws Exception {
        String bearer = adminBearer();
        String name = "terminal-admin-" + Long.toHexString(System.nanoTime());
        assertThat(send("POST", "/api/admin/users", bearer, "{\"username\":\"" + name
                + "\",\"displayName\":\"Another admin\",\"role\":\"ADMIN\",\"initialPassword\":\"another-long-secret-42\"}"))
                .startsWith("201 ");
        String login = send("POST", "/api/auth/login", "", "{\"username\":\"" + name + "\",\"password\":\"another-long-secret-42\"}");
        String other = "Bearer " + json.readTree(login.substring(login.indexOf(' ') + 1)).path("token").asString();

        Recorder recorder = new Recorder();
        WebSocketSession socket = open(recorder, ticket(bearer, "{\"shell\":\"test\"}"));
        await("the shell's first line", () -> recorder.output.toString().contains("hello-terminal"));
        String session = recorder.session();
        socket.close(CloseStatus.GOING_AWAY);

        assertThat(sessions(other)).isEmpty();
        assertThat(send("POST", "/api/terminal/tickets", other, "{\"attach\":\"" + session + "\"}")).startsWith("404 ");
        assertThat(send("DELETE", "/api/terminal/sessions/" + session, other, null)).startsWith("404 ");

        // Still the owner's, who closes it.
        assertThat(sessions(bearer).toString()).contains(session);
        assertThat(send("DELETE", "/api/terminal/sessions/" + session, bearer, null)).startsWith("204 ");
    }

    @Test
    void aTicketNeedsAShellOrAShellToAttach() throws Exception {
        assertThat(send("POST", "/api/terminal/tickets", adminBearer(), "{}")).startsWith("400 ");
        assertThat(send("POST", "/api/terminal/tickets", adminBearer(), "{\"attach\":\"no-such-terminal\"}"))
                .startsWith("404 ").contains("terminal-not-found");
    }

    @Test
    void anythingBeforeATicketClosesTheSocket() throws Exception {
        Recorder recorder = new Recorder();
        WebSocketSession socket = connect(recorder);
        socket.sendMessage(new TextMessage("{\"type\":\"input\",\"data\":\"dir\\r\"}"));
        assertThat(recorder.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void anOperatorGetsNoTicketAndSeesNoShells() throws Exception {
        String operator = "Bearer test-operator-token-0123456789";
        assertThat(send("POST", "/api/terminal/tickets", operator, "{\"shell\":\"test\"}")).startsWith("403 ");
        assertThat(send("GET", "/api/terminal/sessions", operator, null)).startsWith("403 ");
    }
}
