package com.aicompany.backend.task.controller;

import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.task.exception.ArchivedProjectCannotReceiveTasksException;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What {@link TaskExceptionHandler} is allowed to intercept, and where.
 *
 * <p>This exists because the obvious test does not work. Asserting through
 * MockMvc that {@code POST /api/tasks {}} still answers with Spring's default
 * error body is vacuous: the default handling calls {@code sendError} without
 * dispatching to {@code /error}, so MockMvc records an <em>empty</em> body and
 * any {@code doesNotExist()} on it passes regardless of what the running
 * application actually returns. A test written that way would stay green even if
 * the contract it claims to protect were rewritten.
 *
 * <p>So the decision is pinned where it is actually made -- in the annotations --
 * rather than in a response that the test harness cannot observe.
 *
 * <p>ADR-005 §8: the advice handles only the exceptions TASK-003 introduces.
 * Bean Validation is deliberately left to Spring, so the 400 that
 * {@code POST /api/tasks} has returned since TASK-001 keeps its shape. Making the
 * whole API speak one error dialect is TD-07, a decision of its own.
 */
class TaskExceptionHandlerScopeTest {

    @Test
    void theAdviceHandlesOnlyTheExceptionsTaskThreeIntroduces() {

        // An exact set, so adding a handler is a deliberate act that has to come
        // with a reason -- not something that arrives with an unrelated change.
        assertThat(handledExceptionTypes())
                .containsExactlyInAnyOrder(
                        TaskNotFoundException.class,
                        ProjectNotFoundException.class,
                        ArchivedProjectCannotReceiveTasksException.class);
    }

    @Test
    void beanValidationFailuresAreLeftToSpring() {

        // Stated separately from the set above, and checked by assignability
        // rather than by identity: a handler for BindException, or for
        // RuntimeException, or for Exception would swallow
        // MethodArgumentNotValidException just as effectively as declaring it,
        // and would change the 400 of POST /api/tasks for every existing client.
        assertThat(handledExceptionTypes())
                .noneMatch(handled -> handled.isAssignableFrom(MethodArgumentNotValidException.class));
    }

    @Test
    void theAdviceIsScopedToTheTwoControllersThatNeedIt() {

        // Not global. Widening this would change the responses the agent and
        // project endpoints already return, which is exactly what the scoping on
        // the project side exists to prevent as well.
        RestControllerAdvice advice = TaskExceptionHandler.class.getAnnotation(RestControllerAdvice.class);

        assertThat(advice).isNotNull();
        assertThat(advice.assignableTypes())
                .containsExactlyInAnyOrder(TaskController.class, ProjectTaskController.class);

        // An advice with no selector at all is global. Belt and braces: if
        // somebody empties assignableTypes, the assertion above fails, and if
        // they move the selector to basePackages this one does.
        assertThat(advice.basePackages()).isEmpty();
        assertThat(advice.annotations()).isEmpty();
    }

    /**
     * Every exception type the advice intercepts. An {@code @ExceptionHandler}
     * with no value takes its type from the method parameter, so both forms are
     * read here rather than only the declared one.
     */
    private static Set<Class<?>> handledExceptionTypes() {

        Set<Class<?>> handled = new LinkedHashSet<>();

        for (Method method : TaskExceptionHandler.class.getDeclaredMethods()) {

            ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation == null) {
                continue;
            }

            if (annotation.value().length > 0) {
                handled.addAll(Arrays.asList(annotation.value()));
            } else {
                Arrays.stream(method.getParameterTypes())
                        .filter(Throwable.class::isAssignableFrom)
                        .forEach(handled::add);
            }
        }

        return handled;
    }
}
