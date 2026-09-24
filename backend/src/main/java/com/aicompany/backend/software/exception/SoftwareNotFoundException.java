package com.aicompany.backend.software.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class SoftwareNotFoundException extends ProblemException {

    public SoftwareNotFoundException(String key) {
        super(ApiProblem.SOFTWARE_NOT_FOUND, "No software with key '" + key + "' in the catalog");
    }
}
