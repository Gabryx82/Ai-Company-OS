package com.aicompany.backend.software.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class LaunchFailedException extends ProblemException {

    public LaunchFailedException(String detail) {
        super(ApiProblem.LAUNCH_FAILED, detail);
    }
}
