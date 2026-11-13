package com.aicompany.backend.sync;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 37 (ADR-043): the real client, against a server on the loopback. The live smoke
 * found what no scripted answer could: left to itself Java's client offers an upgrade to
 * HTTP/2 on a plain connection, and Open WebUI's server then read every POST without its
 * body and answered 422. The client speaks HTTP/1.1 and offers nothing.
 */
class HttpSyncHttpTest {

    private HttpServer server;
    private final Map<String, List<String>> headers = new ConcurrentHashMap<>();
    private volatile String body;
    private volatile String method;

    @BeforeEach
    void aToolOnTheLoopback() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models/create", exchange -> {
            method = exchange.getRequestMethod();
            headers.clear();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), values));
            body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] answer = "{\"id\":\"aicos-x\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.createContext("/moved", exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.invalid/elsewhere");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path);
    }

    @Test
    void aPostArrivesWithItsBodyAndWithoutAnOfferToChangeProtocol() {
        SyncHttp.Response response = new HttpSyncHttp().send("POST", uri("/api/v1/models/create"), "sk-key", "{\"id\":\"aicos-x\"}");

        assertThat(response.ok()).isTrue();
        assertThat(response.body()).isEqualTo("{\"id\":\"aicos-x\"}");
        assertThat(method).isEqualTo("POST");
        assertThat(body).isEqualTo("{\"id\":\"aicos-x\"}");
        assertThat(headers).doesNotContainKeys("upgrade", "http2-settings");
        assertThat(headers.get("authorization")).containsExactly("Bearer sk-key");
        assertThat(headers.get("content-type")).containsExactly("application/json");
    }

    @Test
    void aCallWithoutAKeyCarriesNoAuthorization() {
        new HttpSyncHttp().send("POST", uri("/api/v1/models/create"), null, "{}");

        assertThat(headers).doesNotContainKey("authorization");
    }

    @Test
    void aRedirectIsAnAnswerNotARoadTheKeyFollows() {
        SyncHttp.Response response = new HttpSyncHttp().send("GET", uri("/moved"), "sk-key", null);

        assertThat(response.status()).isEqualTo(302);
    }

    @Test
    void aToolThatIsNotThereIsUnavailableAndOnlyHttpIsSpoken() {
        int free = server.getAddress().getPort();
        server.stop(0);

        assertThatThrownBy(() -> new HttpSyncHttp().send("GET", URI.create("http://127.0.0.1:" + free + "/health"), null, null))
                .isInstanceOf(SyncProblemException.class).hasMessageContaining("non risponde");
        assertThatThrownBy(() -> new HttpSyncHttp().send("GET", URI.create("file:///c:/x"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HttpSyncHttp().send("GET", URI.create("http://user:pw@127.0.0.1/x"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
