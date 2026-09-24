package com.aicompany.backend.software.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class SoftwareNotLaunchableException extends ProblemException {

    public SoftwareNotLaunchableException(String detail) {
        super(ApiProblem.SOFTWARE_NOT_LAUNCHABLE, detail);
    }
}
