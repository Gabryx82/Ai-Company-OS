package com.aicompany.backend.support;

import com.aicompany.backend.harness.library.RemoteText;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/** The web, as the skill import sees it in tests: a scripted answer, and a record of what was asked. */
public class FakeRemoteText implements RemoteText {

    private final List<URI> fetched = new ArrayList<>();
    private Fetched answer = new Fetched("text/markdown", "# Empty\n");

    public void answer(String contentType, String body) {
        this.answer = new Fetched(contentType, body);
    }

    public List<URI> fetched() {
        return fetched;
    }

    public void reset() {
        fetched.clear();
        answer = new Fetched("text/markdown", "# Empty\n");
    }

    @Override
    public Fetched fetch(URI uri, int maxBytes) {
        fetched.add(uri);
        return answer;
    }
}
