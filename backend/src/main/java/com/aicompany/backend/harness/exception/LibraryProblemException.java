package com.aicompany.backend.harness.exception;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** Refusals of the file-based library (ADR-026). */
public class LibraryProblemException extends ProblemException {

    private LibraryProblemException(ApiProblem problem, String detail, Map<String, String> errors) {
        super(problem, detail, errors);
    }

    public static LibraryProblemException notFileBacked(String key) {
        return new LibraryProblemException(ApiProblem.RESOURCE_NOT_FILE_BACKED,
                "'" + key + "' is not a skill or a knowledge entry: it has no file", Map.of());
    }

    public static LibraryProblemException invalidDocument(String why) {
        return new LibraryProblemException(ApiProblem.SKILL_DOCUMENT_INVALID, why, Map.of("content", why));
    }

    public static LibraryProblemException importRefused(String why) {
        return new LibraryProblemException(ApiProblem.SKILL_IMPORT_REFUSED, why, Map.of("url", why));
    }

    public static LibraryProblemException unavailable(String why) {
        return new LibraryProblemException(ApiProblem.LIBRARY_UNAVAILABLE, why, Map.of());
    }
}
