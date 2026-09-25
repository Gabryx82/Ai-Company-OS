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
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 22 (ADR-029): a real pseudo-terminal behind a real WebSocket. The shell
 * is a harmless test command (an echo), enabled only by a test property.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import({PostgresTestcontainerConfig.class, ScriptedEngineConfiguration.class, FakeHostConfiguration.class})
class TerminalSocketTest {

    @DynamicPropertySource
    static void aHarmlessShell(DynamicPropertyRegistry registry) {
        boolean windows = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
        registry.add("aicos.terminal.test-command", () -> windows
                ? "cmd.exe,/c,echo hello-terminal" : "/bin/echo,hello-terminal");
    }

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    private String post(String path, String bearer, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        if (!bearer.isEmpty()) {
            request.header("Authorization", bearer);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + " " + response.body();
    }

    private String adminBearer() throws Exception {
        String answer = post("/api/auth/login", "", "{\"username\":\"admin\",\"password\":\"" + AdminSession.PASSWORD + "\"}");
        return "Bearer " + json.readTree(answer.substring(answer.indexOf(' ') + 1)).path("token").asString();
    }

    private String ticket(String bearer) throws Exception {
        String answer = post("/api/terminal/tickets", bearer, "{\"shell\":\"test\"}");
        assertThat(answer).startsWith("200 ");
        return json.readTree(answer.substring(4)).path("ticket").asString();
    }

    /** Collects everything a socket receives until it closes. */
    static final class Recorder extends AbstractWebSocketHandler {
        final StringBuilder text = new StringBuilder();
        final StringBuilder output = new StringBuilder();
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
    }

    private WebSocketSession connect(Recorder recorder) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin("http://console.test");
        return new StandardWebSocketClient().execute(recorder, headers,
                URI.create("ws://127.0.0.1:" + port + TerminalConfiguration.SOCKET_PATH)).get(10, TimeUnit.SECONDS);
    }

    @Test
    void anAdminsTicketOpensAPseudoTerminalWhoseOutputStreamsBack() throws Exception {
        String ticket = ticket(adminBearer());
        Recorder recorder = new Recorder();
        WebSocketSession socket = connect(recorder);
        socket.sendMessage(new TextMessage("{\"type\":\"auth\",\"ticket\":\"" + ticket + "\",\"cols\":100,\"rows\":20}"));

        CloseStatus status = recorder.closed.get(30, TimeUnit.SECONDS);
        assertThat(recorder.text.toString()).contains("\"type\":\"ready\"").contains("\"type\":\"exit\"");
        assertThat(recorder.output.toString()).contains("hello-terminal");
        assertThat(status.getCode()).isEqualTo(CloseStatus.NORMAL.getCode());

        // The ticket was single-use.
        Recorder again = new Recorder();
        WebSocketSession second = connect(again);
        second.sendMessage(new TextMessage("{\"type\":\"auth\",\"ticket\":\"" + ticket + "\"}"));
        assertThat(again.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
        assertThat(again.output.toString()).isEmpty();
    }

    @Test
    void anythingBeforeATicketClosesTheSocket() throws Exception {
        Recorder recorder = new Recorder();
        WebSocketSession socket = connect(recorder);
        socket.sendMessage(new TextMessage("{\"type\":\"input\",\"data\":\"dir\\r\"}"));
        assertThat(recorder.closed.get(10, TimeUnit.SECONDS).getCode()).isEqualTo(CloseStatus.POLICY_VIOLATION.getCode());
    }

    @Test
    void anOperatorGetsNoTicket() throws Exception {
        String answer = post("/api/terminal/tickets", "Bearer test-operator-token-0123456789", "{\"shell\":\"test\"}");
        assertThat(answer).startsWith("403 ");
    }
}
