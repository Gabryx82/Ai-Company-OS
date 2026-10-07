package com.aicompany.backend.support;

import com.aicompany.backend.harness.hub.HubProblemException;
import com.aicompany.backend.harness.hub.HubWeb;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The marketplaces, as the Knowledge Hub sees them in tests (PHASE 31): scripted
 * answers by URI, everything else a 404, and a record of what was asked. A host
 * can be made unreachable to prove one failing marketplace hides nothing else.
 */
public class FakeHubWeb implements HubWeb {

    private final Map<String, Response> answers = new ConcurrentHashMap<>();
    private final List<URI> asked = new CopyOnWriteArrayList<>();
    private final List<String> down = new CopyOnWriteArrayList<>();

    public void answer(String uri, String body) {
        answers.put(uri, new Response(200, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of()));
    }

    public void answer(String uri, int status, String body, Map<String, String> headers) {
        answers.put(uri, new Response(status, "application/json", body.getBytes(StandardCharsets.UTF_8), headers));
    }

    public void down(String host) {
        down.add(host);
    }

    public List<URI> asked() {
        return asked;
    }

    public void reset() {
        answers.clear();
        asked.clear();
        down.clear();
    }

    @Override
    public Response get(URI uri, int maxBytes) {
        if (!"https".equals(uri.getScheme()) || !HOSTS.contains(uri.getHost())) {
            throw new IllegalArgumentException("Not an allowed marketplace URI: " + uri);
        }
        asked.add(uri);
        if (down.contains(uri.getHost())) {
            throw HubProblemException.marketplace(uri.getHost() + " non risponde");
        }
        Response answer = answers.get(uri.toString());
        if (answer == null) {
            return new Response(404, "text/plain", "Not Found".getBytes(StandardCharsets.UTF_8), Map.of());
        }
        if (answer.body().length > maxBytes) {
            throw HubProblemException.packageRefused("A file of the package is larger than " + maxBytes + " bytes");
        }
        return answer;
    }
}
