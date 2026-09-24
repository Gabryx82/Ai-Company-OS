package com.aicompany.backend.software.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class LauncherUnavailableException extends ProblemException {

    public LauncherUnavailableException(String detail) {
        super(ApiProblem.LAUNCHER_UNAVAILABLE, detail);
    }
}
