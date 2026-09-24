package com.aicompany.backend.harness.library;

import com.aicompany.backend.harness.exception.LibraryProblemException;
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
 * The real fetcher: HTTPS only (checked by the caller), no redirects followed
 * -- a redirect could lead to a host the caller never validated -- a short
 * timeout and a size cap enforced while reading.
 */
@Component
class HttpRemoteText implements RemoteText {

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Override
    public Fetched fetch(URI uri, int maxBytes) {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
                .header("Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1")
                .header("User-Agent", "AI-Company-OS skill import")
                .GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                throw LibraryProblemException.importRefused("The URL answered HTTP " + response.statusCode()
                        + (response.statusCode() / 100 == 3 ? " (a redirect: use the final URL, e.g. the raw file)" : ""));
            }
            try (InputStream in = response.body()) {
                byte[] bytes = in.readNBytes(maxBytes + 1);
                if (bytes.length > maxBytes) {
                    throw LibraryProblemException.importRefused("The document is larger than " + maxBytes + " bytes");
                }
                return new Fetched(response.headers().firstValue("Content-Type").orElse(""),
                        new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw LibraryProblemException.importRefused("The URL could not be read: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw LibraryProblemException.importRefused("The download was interrupted");
        }
    }
}
