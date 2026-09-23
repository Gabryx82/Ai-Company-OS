package com.aicompany.backend.run.service;

import com.aicompany.backend.agent.exception.AgentNotFoundException;
import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.project.exception.ProjectNotFoundException;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.project.repository.ProjectRepository;
import com.aicompany.backend.run.dto.RunResponse;
import com.aicompany.backend.run.exception.RunNotFoundException;
import com.aicompany.backend.run.exception.TaskRunInProgressException;
import com.aicompany.backend.run.execution.RunPrompt;
import com.aicompany.backend.run.model.RunStatus;
import com.aicompany.backend.run.model.TaskRun;
import com.aicompany.backend.run.repository.TaskRunRepository;
import com.aicompany.backend.task.exception.TaskNotFoundException;
import com.aicompany.backend.task.model.Task;
import com.aicompany.backend.task.model.TaskStatus;
import com.aicompany.backend.task.model.TaskTransition;
import com.aicompany.backend.task.repository.TaskRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * Launching a run, and reading runs (ADR-016).
 *
 * <p>Launching is a write to the <em>task</em> -- it may start it -- so it follows
 * the task's protocol exactly: L0 on the task, P1 against the task's tag, L2 on
 * its project and agent, rules on the entity. The run row is inserted inside the
 * same transaction, and nobody executes it until that transaction has committed.
 */
@Service
@Transactional
public class RunService {

    private static final EnumSet<RunStatus> UNFINISHED = EnumSet.of(RunStatus.QUEUED, RunStatus.RUNNING);

    private final TaskRunRepository runs;
    private final TaskRepository tasks;
    private final ProjectRepository projects;
    private final AgentRepository agents;
    private final ApplicationEventPublisher events;

    public RunService(TaskRunRepository runs, TaskRepository tasks, ProjectRepository projects,
                      AgentRepository agents, ApplicationEventPublisher events) {
        this.runs = runs;
        this.tasks = tasks;
        this.projects = projects;
        this.agents = agents;
        this.events = events;
    }

    /**
     * Records a run of a task by its agent, starting the task if it is open.
     *
     * <ol>
     *   <li><strong>L0</strong> on the task, then <strong>P1</strong> on its tag:
     *       launching may move the task, so a caller that is out of date about the
     *       task is out of date about this;</li>
     *   <li><strong>L2</strong> on the project and on the agent, shared, in that
     *       order (L5');</li>
     *   <li>the rules, on the task: not frozen, not done, held by an active agent;</li>
     *   <li>one unfinished run per task -- checked under L0, so two launches cannot
     *       both pass; the partial unique index is the net;</li>
     *   <li>{@code OPEN} → {@code IN_PROGRESS} through the lifecycle's own edge,
     *       never by writing the status;</li>
     *   <li>the run is inserted {@code QUEUED} and {@link RunQueued} is published,
     *       to be delivered after the commit.</li>
     * </ol>
     *
     * <p>{@code requestedModel} null means: the agent's model if it has one,
     * otherwise the engine's default.
     */
    public RunResponse launch(Long taskId, String requestedModel, String requestedBy, Precondition precondition) {

        Task task = tasks.findByIdForUpdate(taskId).orElseThrow(() -> new TaskNotFoundException(taskId));
        precondition.requireSatisfiedBy(task.getVersion());

        Long projectId = task.getProjectId();
        Project project = projectId == null ? null : projects.findByIdForShare(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));

        Long agentId = task.getAgentId();
        Agent agent = agentId == null ? null : agents.findByIdForShare(agentId)
                .orElseThrow(() -> new AgentNotFoundException(agentId));

        task.requireRunnable(project, agent);

        if (runs.existsByTaskIdAndStatusIn(taskId, UNFINISHED)) {
            throw new TaskRunInProgressException(taskId);
        }

        if (task.getStatus() == TaskStatus.OPEN) {
            task.apply(TaskTransition.START, project, agent);
        }

        // The model: the one asked for, else the agent's, else the engine's default
        // (null). What is recorded is what is sent.
        String model = requestedModel != null ? requestedModel : agent.getModel();

        RunPrompt prompt = RunPrompt.of(task, agent, project);
        TaskRun run = runs.saveAndFlush(new TaskRun(taskId, agent.getId(), model,
                prompt.system(), prompt.user(), "run-" + UUID.randomUUID(), requestedBy));
        tasks.saveAndFlush(task);

        events.publishEvent(new RunQueued(run.getId()));
        return RunResponse.from(run);
    }

    @Transactional(readOnly = true)
    public RunResponse findById(Long runId) {
        return RunResponse.from(runs.findById(runId).orElseThrow(() -> new RunNotFoundException(runId)));
    }

    /** Newest first. An unknown task is a 404, not an empty list. */
    @Transactional(readOnly = true)
    public List<RunResponse> findByTask(Long taskId) {
        if (!tasks.existsById(taskId)) {
            throw new TaskNotFoundException(taskId);
        }
        return runs.findAllByTaskIdOrderByIdDesc(taskId).stream().map(RunResponse::from).toList();
    }
}
