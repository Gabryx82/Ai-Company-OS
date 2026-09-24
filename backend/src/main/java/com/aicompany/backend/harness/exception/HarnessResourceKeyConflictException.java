package com.aicompany.backend.harness.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class HarnessResourceKeyConflictException extends ProblemException {

    public HarnessResourceKeyConflictException(String detail) {
        super(ApiProblem.HARNESS_RESOURCE_KEY_CONFLICT, detail);
    }
}
