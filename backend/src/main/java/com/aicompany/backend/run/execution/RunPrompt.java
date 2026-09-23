package com.aicompany.backend.run.execution;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.model.Task;

/**
 * The two prompts a run sends, built from the registry and nothing else.
 *
 * <p>Stored on the run verbatim ({@code system_prompt}, {@code user_prompt}), so
 * that what a model was asked is part of the record -- not reconstructed later
 * from rows that may have changed since.
 */
public record RunPrompt(String system, String user) {

    public static RunPrompt of(Task task, Agent agent, Project project) {

        String system = """
                You are %s, %s in AI Company OS.
                Specialization: %s.
                You are working on exactly one task. Answer with the work product itself -- a concrete \
                plan, design or result that a human operator will review before the task is closed. \
                Be precise and concise. If the task cannot be done as written, say what is missing."""
                .formatted(agent.getName(), agent.getRole(), agent.getSpecialization());

        String user = """
                Task #%d: %s
                Priority: %s
                Project: %s

                %s"""
                .formatted(task.getId(), task.getTitle(), task.getPriority(),
                        project == null ? "none" : project.getName(),
                        task.getDescription() == null || task.getDescription().isBlank()
                                ? "(no description)" : task.getDescription());

        return new RunPrompt(system, user);
    }
}
