package com.aicompany.backend.deletion;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** A delete without the resource's name typed back (ADR-027 §2). */
public class DeletionConfirmationException extends ProblemException {

    public DeletionConfirmationException(String name) {
        super(ApiProblem.DELETE_CONFIRMATION_MISMATCH,
                "To delete it, type its name exactly: «" + name + "»", Map.of("confirm", "must be «" + name + "»"));
    }
}
