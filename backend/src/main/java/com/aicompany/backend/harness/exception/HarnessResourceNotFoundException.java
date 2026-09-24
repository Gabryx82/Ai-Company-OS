package com.aicompany.backend.harness.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class HarnessResourceNotFoundException extends ProblemException {

    public HarnessResourceNotFoundException(String detail) {
        super(ApiProblem.HARNESS_RESOURCE_NOT_FOUND, detail);
    }
}
