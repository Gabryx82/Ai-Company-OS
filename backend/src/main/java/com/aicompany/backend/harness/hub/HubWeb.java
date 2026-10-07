package com.aicompany.backend.harness.hub;

import java.net.URI;
import java.util.Map;
import java.util.Set;

/**
 * The online catalogs, as the Hub reaches them (ADR-036 §6). An interface so
 * that tests never touch the network. Only {@link #HOSTS} are ever asked, over
 * HTTPS, and every URI is built by the Hub from validated parts -- never taken
 * from a request.
 */
public interface HubWeb {

    Set<String> HOSTS = Set.of("skills.sh", "clawhub.ai", "api.github.com", "raw.githubusercontent.com");

    /** What came back: the status, a few headers (lower-case names) and at most the requested bytes. */
    record Response(int status, String contentType, byte[] body, Map<String, String> headers) {
        public boolean ok() {
            return status / 100 == 2;
        }

        public String text() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /**
     * GETs a URI of one of the {@link #HOSTS}. A body larger than {@code maxBytes}
     * is refused; a network failure is a {@link HubProblemException#marketplace}.
     */
    Response get(URI uri, int maxBytes);
}
