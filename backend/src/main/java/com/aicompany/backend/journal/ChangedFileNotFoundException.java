package com.aicompany.backend.journal;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

/** PHASE 37 (ADR-047): the file did not change since the task was handed off, or there is no copy to compare with. */
public class ChangedFileNotFoundException extends ProblemException {
    public ChangedFileNotFoundException(long taskId, String path) {
        super(ApiProblem.RESOURCE_NOT_FOUND, "No change to '" + path + "' in the work of task " + taskId);
    }
}
