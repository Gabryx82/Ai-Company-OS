package com.aicompany.backend.workspace.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/** PHASE 33 (ADR-040): the explorer never writes over a name that is already there. */
public class WorkspaceEntryExistsException extends ProblemException {

    public WorkspaceEntryExistsException(String detail) {
        super(ApiProblem.WORKSPACE_ENTRY_EXISTS, detail);
    }
}
