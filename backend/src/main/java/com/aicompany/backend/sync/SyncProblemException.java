package com.aicompany.backend.sync;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** Refusals of the agent synchronisation with the tools outside AI Company OS (ADR-043). */
public class SyncProblemException extends ProblemException {

    private SyncProblemException(ApiProblem problem, String detail) {
        super(problem, detail, Map.of());
    }

    /** No connector with that key. */
    public static SyncProblemException unknownTool(String key) {
        return new SyncProblemException(ApiProblem.SYNC_TOOL_NOT_FOUND, "No tool '" + key + "' receives agents");
    }

    /** The tool is not installed, not configured, or does not answer. */
    public static SyncProblemException unavailable(String why) {
        return new SyncProblemException(ApiProblem.SYNC_TOOL_UNAVAILABLE, why);
    }

    /** The tool answered, and refused: a rejected configuration, an identifier it does not accept. */
    public static SyncProblemException refused(String why) {
        return new SyncProblemException(ApiProblem.SYNC_REFUSED, why);
    }
}
