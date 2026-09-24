package com.aicompany.backend.run;

import com.aicompany.backend.run.engine.EngineClient;
import com.aicompany.backend.run.engine.EngineFailure;
import com.aicompany.backend.run.engine.EngineProperties;
import com.aicompany.backend.run.engine.HttpEngineClient;
import com.aicompany.backend.run.engine.RunFailures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The client side of contract v1, against a real socket (ADR-015 §3, ADR-016 §4).
 *
 * <p>No Spring, no mocks of the HTTP layer: a JDK {@code HttpServer} on an
 * ephemeral port plays the engine, so what is asserted is what actually goes over
 * the wire -- headers, body, and the reading of every kind of answer.
 */
class HttpEngineClientTest {

    private static final String TOKEN = "engine-token-for-this-test";

    private final JsonMapper json = JsonMapper.builder().build();
    private HttpServer server;
    private final AtomicReference<HttpExchange> lastExchange = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile Responder responder;

    interface Responder {
        void respond(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void startEngine() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastExchange.set(exchange);
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder.respond(exchange);
        });
        server.start();
    }

    @AfterEach
    void stopEngine() {
        server.stop(0);
    }

    @Test
    void aCompletionIsSentAsV1AndReadBack() {

        responder = reply(200, "application/json", """
                {"id":"cmpl_1","model":"ollama:llama3.2:3b","provider":"ollama","output":"The plan",
                 "finish_reason":"stop","usage":{"input_tokens":58,"output_tokens":107},
                 "latency_ms":13061,"correlation_id":"run-1"}""");

        EngineClient.Completion completion = client(Duration.ofSeconds(5)).complete(request("ollama:llama3.2:3b"));

        assertThat(completion).isEqualTo(new EngineClient.Completion("The plan", "stop", "ollama:llama3.2:3b",
                58, 107, 13061));

        HttpExchange exchange = lastExchange.get();
        assertThat(exchange.getRequestMethod()).isEqualTo("POST");
        assertThat(exchange.getRequestURI().getPath()).isEqualTo("/v1/completions");
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer " + TOKEN);
        assertThat(exchange.getRequestHeaders().getFirst("X-Correlation-Id")).isEqualTo("run-1");

        JsonNode body = json.readTree(lastBody.get());
        assertThat(body.get("model").asString()).isEqualTo("ollama:llama3.2:3b");
        assertThat(body.get("system").asString()).isEqualTo("You are the planner");
        assertThat(body.get("messages").get(0).get("role").asString()).isEqualTo("user");
        assertThat(body.get("messages").get(0).get("content").asString()).isEqualTo("Plan it");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(512);
        assertThat(body.get("metadata").get("run_id").asString()).isEqualTo("1");
    }

    /** PHASE 10: a plain request carries neither field; a planning request carries both, the schema as an object. */
    @Test
    void theJsonFormatAndItsSchemaTravelOnlyWhenAsked() {

        responder = reply(200, "application/json", """
                {"output":"{}","finish_reason":"stop","model":"m","usage":{"input_tokens":1,"output_tokens":1}}""");

        client(Duration.ofSeconds(5)).complete(request("m"));
        JsonNode plain = json.readTree(lastBody.get());
        assertThat(plain.has("response_format")).isFalse();
        assertThat(plain.has("response_schema")).isFalse();

        client(Duration.ofSeconds(5)).complete(new EngineClient.Request("m", "s", "u", 64, "plan-1", Map.of(), "json",
                "{\"type\":\"object\",\"required\":[\"phases\"]}"));
        JsonNode planning = json.readTree(lastBody.get());
        assertThat(planning.get("response_format").asString()).isEqualTo("json");
        assertThat(planning.get("response_schema").get("required").get(0).asString()).isEqualTo("phases");
    }

    @Test
    void noModelMeansTheFieldIsAbsentAndTheEngineChooses() {

        responder = reply(200, "application/json", """
                {"output":"x","finish_reason":"stop","model":"echo:default","usage":{"input_tokens":1,"output_tokens":1}}""");

        client(Duration.ofSeconds(5)).complete(request(null));
        assertThat(json.readTree(lastBody.get()).has("model")).isFalse();
    }

    @Test
    void anEngineProblemIsRelayedWithItsOwnType() {

        responder = reply(400, "application/problem+json", """
                {"type":"urn:ai-company-os:engine:problem:unknown-model","title":"Unknown model","status":400,
                 "detail":"Ollama has no model 'nope'; run `ollama pull nope`"}""");

        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request("ollama:nope")))
                .isInstanceOfSatisfying(EngineFailure.class, failure -> {
                    assertThat(failure.type()).isEqualTo("urn:ai-company-os:engine:problem:unknown-model");
                    assertThat(failure.detail()).contains("ollama pull nope");
                });
    }

    @Test
    void anErrorThatIsNotAnEngineProblemIsAProtocolFailureNotARelay() {

        responder = reply(502, "application/json", "{\"type\":\"urn:someone-else:problem\",\"detail\":\"x\"}");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request(null)))
                .isInstanceOfSatisfying(EngineFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(RunFailures.ENGINE_PROTOCOL));

        responder = reply(502, "text/html", "<html>bad gateway</html>");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request(null)))
                .isInstanceOfSatisfying(EngineFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(RunFailures.ENGINE_PROTOCOL));
    }

    @Test
    void aSuccessWithoutTheContractFieldsIsAProtocolFailure() {

        responder = reply(200, "application/json", "{\"output\":\"x\"}");
        assertThatThrownBy(() -> client(Duration.ofSeconds(5)).complete(request(null)))
                .isInstanceOfSatisfying(EngineFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(RunFailures.ENGINE_PROTOCOL));
    }

    @Test
    void nothingListeningIsUnreachable() throws IOException {

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        EngineProperties properties = properties(Duration.ofSeconds(5));
        properties.setUrl("http://127.0.0.1:" + closedPort);

        assertThatThrownBy(() -> new HttpEngineClient(properties).complete(request(null)))
                .isInstanceOfSatisfying(EngineFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(RunFailures.ENGINE_UNREACHABLE));
    }

    @Test
    void anEngineSlowerThanTheReadTimeoutIsATimeout() {

        responder = exchange -> {
            try {
                Thread.sleep(1_500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            reply(200, "application/json", "{}").respond(exchange);
        };

        assertThatThrownBy(() -> client(Duration.ofMillis(300)).complete(request(null)))
                .isInstanceOfSatisfying(EngineFailure.class,
                        failure -> assertThat(failure.type()).isEqualTo(RunFailures.ENGINE_TIMEOUT));
    }

    @Test
    void aClientWithoutATokenRefusesToExist() {

        EngineProperties properties = properties(Duration.ofSeconds(1));
        properties.setToken("short");
        assertThatThrownBy(() -> new HttpEngineClient(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aicos.engine.token");
    }

    // --- helpers -----------------------------------------------------------

    private HttpEngineClient client(Duration readTimeout) {
        return new HttpEngineClient(properties(readTimeout));
    }

    private EngineProperties properties(Duration readTimeout) {
        EngineProperties properties = new EngineProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        properties.setToken(TOKEN);
        properties.setReadTimeout(readTimeout);
        return properties;
    }

    private static EngineClient.Request request(String model) {
        return new EngineClient.Request(model, "You are the planner", "Plan it", 512, "run-1", Map.of("run_id", "1"));
    }

    private static Responder reply(int status, String contentType, String body) {
        return exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        };
    }
}
