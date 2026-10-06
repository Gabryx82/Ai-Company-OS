package com.aicompany.backend.deletion;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.List;

/**
 * PHASE 30 (ADR-035): a delete refused because something depends on what it
 * would remove and cannot be detached. The detail lists the blockers, the same
 * sentences the preview shows, so the console and a direct API caller read the
 * same reason.
 */
public class DeletionBlockedException extends ProblemException {

    public DeletionBlockedException(List<String> blockers) {
        super(ApiProblem.DELETION_BLOCKED, String.join(" ", blockers));
    }

    public DeletionBlockedException(String blocker) {
        this(List.of(blocker));
    }
}
