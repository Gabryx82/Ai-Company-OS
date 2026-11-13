package com.aicompany.backend.support;

import com.aicompany.backend.sync.SyncHttp;
import com.aicompany.backend.sync.SyncProblemException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The tools the agent synchronisation reaches over HTTP, as tests see them (PHASE 37):
 * no network. Every call is recorded with what it carried; it is answered by the first
 * route that matches its method and the start of its path -- and with nothing scripted
 * the tool "does not answer", which is how a machine without Open WebUI looks.
 */
public class FakeSyncHttp implements SyncHttp {

    public record Call(String method, URI uri, String bearer, String json) {
        public String path() {
            return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        }
    }

    private record Route(String method, String pathPrefix, Function<Call, Response> answer) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private final List<Route> routes = new CopyOnWriteArrayList<>();

    public void answer(String method, String pathPrefix, int status, String body) {
        routes.addFirst(new Route(method, pathPrefix, call -> new Response(status, body)));
    }

    public void answer(String method, String pathPrefix, Function<Call, Response> answer) {
        routes.addFirst(new Route(method, pathPrefix, answer));
    }

    public List<Call> calls() {
        return new ArrayList<>(calls);
    }

    public void reset() {
        calls.clear();
        routes.clear();
    }

    @Override
    public Response send(String method, URI uri, String bearer, String json) {
        Call call = new Call(method, uri, bearer, json);
        calls.add(call);
        for (Route route : routes) {
            if (route.method().equals(method) && call.path().startsWith(route.pathPrefix())) {
                return route.answer().apply(call);
            }
        }
        throw SyncProblemException.unavailable(uri.getHost() + ":" + uri.getPort() + " non risponde");
    }
}
