package com.aicompany.backend.hitl.lesson;

import com.aicompany.backend.api.ApiProblem;
import com.aicompany.backend.api.ProblemException;

import java.util.Map;

/** PHASE 37 (ADR-044): a lesson, one of its items or a review card that is not there. */
public class LessonNotFoundException extends ProblemException {

    private LessonNotFoundException(String detail) {
        super(ApiProblem.LESSON_NOT_FOUND, detail, Map.of());
    }

    public static LessonNotFoundException lesson(String path) {
        return new LessonNotFoundException("No lesson '" + path + "' in this project");
    }

    public static LessonNotFoundException item(String what) {
        return new LessonNotFoundException(what);
    }
}
