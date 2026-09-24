package com.aicompany.backend.harness.library;

import java.net.URI;

/**
 * Fetches a text document from the web for a skill import (ADR-026 §4). An
 * interface so that tests never reach the network.
 */
public interface RemoteText {

    record Fetched(String contentType, String body) {
    }

    /** Called only with a URI the library has already validated. */
    Fetched fetch(URI uri, int maxBytes);
}
