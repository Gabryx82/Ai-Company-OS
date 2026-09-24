package com.aicompany.backend.workspace.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

public class WorkspaceFileNotFoundException extends ProblemException {

    public WorkspaceFileNotFoundException(String detail) {
        super(ApiProblem.WORKSPACE_FILE_NOT_FOUND, detail);
    }
}
