package com.aicompany.backend.hitl.lesson;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PHASE 37 (ADR-044): the lessons of a project, and what the operator does with them.
 * Every route reads the lesson file and writes only the operator's progress.
 */
@RestController
public class LessonController {

    public record LessonPredictionRequest(@NotBlank @Size(max = 500) String path, @NotBlank @Size(max = 2000) String answer) {
    }

    /** {@code manual}: done on the operator's word, without looking at the file. */
    public record LessonStepRequest(@NotBlank @Size(max = 500) String path, @NotNull @Min(0) Integer step, @Nullable Boolean manual) {
    }

    /** {@code reveal}: give the solution up. Otherwise {@code answer} is required. */
    public record LessonExerciseRequest(@NotBlank @Size(max = 500) String path, @NotNull @Min(0) Integer exercise,
                                  @Nullable @Size(max = 2000) String answer, @Nullable Boolean reveal) {
    }

    public record CardReviewRequest(@NotNull Boolean known) {
    }

    private final LessonService lessons;

    public LessonController(LessonService lessons) {
        this.lessons = lessons;
    }

    /** Every project's lessons, the cards due today and the figures of the learning so far. */
    @GetMapping("/api/learning")
    public LessonService.LearningOverview learningOverview() {
        return lessons.overview();
    }

    @GetMapping("/api/projects/{id}/lessons")
    public List<LessonService.LessonSummary> projectLessons(@PathVariable Long id) {
        return lessons.list(id);
    }

    /** One lesson, with the operator's progress; the solution of an item is held back until it is done. */
    @GetMapping("/api/projects/{id}/lessons/view")
    public LessonService.LessonView viewLesson(@PathVariable Long id, @RequestParam String path) {
        return lessons.view(id, path);
    }

    @PostMapping("/api/projects/{id}/lessons/prediction")
    public LessonService.PredictionView predictOutcome(@PathVariable Long id, @Valid @RequestBody LessonPredictionRequest request,
                                                Authentication caller) {
        return lessons.predict(id, request.path(), request.answer(), caller.getName());
    }

    /** Whether the step's code is in the file the lesson names, in the project's folder. */
    @PostMapping("/api/projects/{id}/lessons/step")
    public LessonService.StepCheck checkStep(@PathVariable Long id, @Valid @RequestBody LessonStepRequest request,
                                             Authentication caller) {
        return lessons.checkStep(id, request.path(), request.step(), Boolean.TRUE.equals(request.manual()), caller.getName());
    }

    @PostMapping("/api/projects/{id}/lessons/exercise")
    public LessonService.ExerciseResult answerExercise(@PathVariable Long id, @Valid @RequestBody LessonExerciseRequest request,
                                               Authentication caller) {
        return lessons.answer(id, request.path(), request.exercise(), request.answer(), Boolean.TRUE.equals(request.reveal()),
                caller.getName());
    }

    /** The concepts of the finished lessons that are due for review today. */
    @GetMapping("/api/learning/review")
    public LessonService.ReviewDeck reviewDeck() {
        return lessons.review();
    }

    /** The back of a card. */
    @GetMapping("/api/learning/review/{cardId}")
    public LessonService.ReviewCard turnCard(@PathVariable Long cardId) {
        return lessons.turn(cardId);
    }

    /** Whether the operator knew it: the card comes back later, or tomorrow. */
    @PostMapping("/api/learning/review/{cardId}")
    public LessonService.ReviewCard reviewCard(@PathVariable Long cardId, @Valid @RequestBody CardReviewRequest request) {
        return lessons.reviewed(cardId, request.known());
    }
}
