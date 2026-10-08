package com.aicompany.backend.harness.hub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The real client: HTTPS to the allowed hosts only, no redirect followed (a
 * redirect could lead anywhere), short timeouts and a size cap enforced while
 * reading.
 */
@Component
class HttpHubWeb implements HubWeb {

    private static final List<String> KEPT_HEADERS = List.of("x-ratelimit-remaining", "x-ratelimit-reset", "retry-after", "etag");

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** PHASE 32 (TD-79): {@code AICOS_GITHUB_TOKEN}, from the operator's local.env; empty asks anonymously. */
    private final String githubToken;

    HttpHubWeb(@Value("${aicos.hub.github-token:}") String githubToken) {
        this.githubToken = githubToken == null ? "" : githubToken.strip();
    }

    @Override
    public boolean githubAuthenticated() {
        return !githubToken.isEmpty();
    }

    @Override
    public Response get(URI uri, int maxBytes) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                || !HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("Not an allowed marketplace URI: " + uri);
        }
        try {
            HttpResponse<InputStream> response = client.send(request(uri, githubToken), HttpResponse.BodyHandlers.ofInputStream());
            Map<String, String> headers = new LinkedHashMap<>();
            for (String name : KEPT_HEADERS) {
                response.headers().firstValue(name).ifPresent(v -> headers.put(name, v));
            }
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw HubProblemException.packageRefused("A file of the package is larger than " + maxBytes + " bytes");
                }
                return new Response(response.statusCode(), response.headers().firstValue("Content-Type").orElse(""),
                        bytes, headers);
            }
        } catch (IOException e) {
            throw HubProblemException.marketplace(uri.getHost() + " non risponde: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HubProblemException.marketplace("La richiesta a " + uri.getHost() + " è stata interrotta");
        }
    }

    /**
     * The request for an allowed URI. The token goes to the GitHub API and to no
     * other host -- not even raw.githubusercontent.com, which a package's files come
     * from and which does not count against the API's limit.
     */
    static HttpRequest request(URI uri, String githubToken) {
        // The MCP Registry answers a search in 2 s or in 30 (measured 2026-10-07): it gets more time.
        Duration timeout = uri.getHost().equals("registry.modelcontextprotocol.io") ? Duration.ofSeconds(45) : Duration.ofSeconds(20);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                .header("User-Agent", "AI-Company-OS Knowledge Hub")
                .GET();
        if (uri.getHost().equals("api.github.com")) {
            request.header("Accept", "application/vnd.github+json");
            if (!githubToken.isBlank()) {
                request.header("Authorization", "Bearer " + githubToken.strip());
            }
        }
        return request.build();
    }
}
