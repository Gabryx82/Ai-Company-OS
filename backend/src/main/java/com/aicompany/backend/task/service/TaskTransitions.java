package com.aicompany.backend.task.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskTransition;
import com.aicompany.backend.task.model.TaskTransitionRecord;
import com.aicompany.backend.task.repository.TaskTransitionRepository;
import org.springframework.stereotype.Component;

/**
 * The one way a task takes an edge of its lifecycle (PHASE 32, TD-38): the rules
 * of {@link Task#apply}, then a {@link TaskTransitionRecord} of who, when and
 * through what, in the same transaction -- a refused transition records nothing,
 * a recorded one happened.
 *
 * <p>Every path that moves a task comes here (the lifecycle routes, a run's
 * launch, a handoff, a review); {@code TaskTransitionHistoryApiTest} holds that
 * nothing else in the code calls {@code Task.apply}. Locking stays the caller's:
 * this is called with the task, its project and its agent already locked in the
 * order of ADR-006 §4.
 */
@Component
public class TaskTransitions {

    private final TaskTransitionRepository history;

    public TaskTransitions(TaskTransitionRepository history) {
        this.history = history;
    }

    public void apply(Task task, TaskTransition transition, Project projectItLivesIn, Agent lockedAgent,
                      String actor, TaskTransitionRecord.Via via) {
        task.apply(transition, projectItLivesIn, lockedAgent);
        history.save(new TaskTransitionRecord(task.getId(), transition, actor, via));
    }
}
