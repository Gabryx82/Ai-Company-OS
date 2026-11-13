package com.aicompany.backend.sync;

import org.jspecify.annotations.Nullable;

import java.net.URI;

/**
 * PHASE 37 (ADR-043): the HTTP calls a connector makes to a tool on this machine. An
 * interface so that tests never touch the network; every URI is built by the
 * connector from the tool's configured address and its own fixed paths.
 */
public interface SyncHttp {

    record Response(int status, String body) {
        public boolean ok() {
            return status / 100 == 2;
        }
    }

    /**
     * @param bearer the operator's key for the tool, sent to that tool only; null for a call without one
     * @param json   the request body, or null for none
     * @throws SyncProblemException when nothing answers
     */
    Response send(String method, URI uri, @Nullable String bearer, @Nullable String json);
}
