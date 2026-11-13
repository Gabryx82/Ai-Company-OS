package com.aicompany.backend.sync;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * The real client: plain HTTP or HTTPS to the address the operator configured for the
 * tool, no redirect followed (a redirect could carry the key anywhere), short
 * timeouts, and at most {@value #MAX_BODY} bytes of an answer kept.
 */
@Component
class HttpSyncHttp implements SyncHttp {

    static final int MAX_BODY = 2 * 1024 * 1024;

    /**
     * HTTP/1.1, said outright. Left to itself the client offers an upgrade to HTTP/2 on a plain
     * connection, and a server that does not take it may read the request without its body:
     * Open WebUI's (uvicorn) answered every POST with 422, as if nothing had been sent
     * (PHASE 37 live smoke).
     */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    @Override
    public Response send(String method, URI uri, @Nullable String bearer, @Nullable String json) {
        if (uri.getHost() == null || uri.getUserInfo() != null
                || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IllegalArgumentException("Not an address a tool can be reached at: " + uri);
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .header("User-Agent", "AI-Company-OS agent sync");
        if (bearer != null && !bearer.isBlank()) {
            request.header("Authorization", "Bearer " + bearer.strip());
        }
        if (json == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        }
        try {
            HttpResponse<InputStream> response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                return new Response(response.statusCode(), new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw SyncProblemException.unavailable(uri.getHost() + ":" + uri.getPort() + " non risponde");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SyncProblemException.unavailable("La richiesta a " + uri.getHost() + " è stata interrotta");
        }
    }
}
