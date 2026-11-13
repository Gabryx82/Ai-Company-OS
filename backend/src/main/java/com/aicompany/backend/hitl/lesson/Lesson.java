package com.aicompany.backend.hitl.lesson;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * PHASE 37 (ADR-044): a lesson as {@link LessonParser} reads it from its file -- with
 * the answers. What the console is given is {@code LessonService.LessonView}, which
 * holds an answer back until the operator has given theirs.
 *
 * @param problems where the file departs from the format: said to the operator, never a refusal
 */
public record Lesson(String title, String intro, @Nullable Prediction prediction, List<Step> steps,
                     List<Exercise> exercises, List<Concept> concepts, List<String> problems) {

    public record Prediction(String question, String answer) {
    }

    /** Who puts the step's code in the file. */
    public enum StepMode {
        /** A file the operator creates. */
        NEW,
        /** A file that exists, where the operator changes a piece. */
        EDIT,
        /** Code the agent already wrote: the operator reads it where it is. */
        DONE
    }

    /** @param code a fragment of one line of the step's code, or null for a remark on the whole */
    public record Line(@Nullable String code, String text) {
    }

    public record Step(String title, @Nullable String file, StepMode mode, @Nullable String language, String code,
                       List<Line> lines, @Nullable String why) {
    }

    public enum Kind { QUIZ, FILL, BUG, EXPLAIN }

    public record Option(String text, boolean correct) {
    }

    /**
     * @param solution  FILL: what goes in the blank, alternatives separated by {@code |};
     *                  BUG: the number of the wrong line
     * @param keyPoints EXPLAIN: what a good answer touches
     */
    public record Exercise(Kind kind, String prompt, List<Option> options, @Nullable String language, @Nullable String code,
                           @Nullable String solution, @Nullable String explanation, List<String> keyPoints) {
    }

    public record Concept(String name, String text) {
    }

    /** How many things the operator does in this lesson: the prediction, each step, each exercise. */
    public int items() {
        return (prediction == null ? 0 : 1) + steps.size() + exercises.size();
    }
}
