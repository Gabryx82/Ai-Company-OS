package com.aicompany.backend.software.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class SoftwareKeyConflictException extends ProblemException {

    public SoftwareKeyConflictException(String key) {
        super(ApiProblem.SOFTWARE_KEY_CONFLICT, "The catalog already has an entry with key '" + key + "'");
    }
}
