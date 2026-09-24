package com.aicompany.backend.plan.service;

import com.aicompany.backend.plan.exception.PlanInvalidException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * {@code .aicos/plan.json}: the canonical, machine-readable implementation plan
 * (ADR-021 §2). Two producers write it -- the AI Engine, through the planner
 * prompt, and an external agent such as Claude Code following a handoff -- and
 * both converge here, so there is one parser and one validator.
 *
 * <p>Lenient about what it does not know (a model adds fields), strict about what
 * it needs: every phase and task has a title, the sizes are bounded, and the
 * result is normalised before anything is written.
 */
public record PlanDocument(Integer version, String summary, List<String> stack, List<PhasePlan> phases) {

    public record PhasePlan(String title, String objective, String scope, String strategy, String architecture,
                            List<String> prerequisites, List<String> agents, List<String> software,
                            List<String> risks, List<String> completionCriteria, List<String> reviewCriteria,
                            List<TaskPlan> tasks) {
    }

    public record TaskPlan(String title, String objective, String why, String scope, String implementation,
                           String agentRole, List<String> subagents, List<String> software, List<String> files,
                           List<String> folders, List<String> contracts, List<String> tests,
                           List<String> completionCriteria, String priority) {
    }

    static final int MAX_PHASES = 12;
    static final int MAX_TASKS_PER_PHASE = 20;
    static final int MAX_TASKS = 120;

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    /**
     * Reads a plan out of text that may wrap it -- a model's Markdown fence, a
     * sentence before the object -- and validates it.
     */
    public static PlanDocument parse(String text) {
        if (text == null || text.isBlank()) {
            throw new PlanInvalidException("The plan is empty", Map.of("plan", "empty"));
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new PlanInvalidException("No JSON object in the plan", Map.of("plan", "no JSON object found"));
        }
        PlanDocument parsed;
        try {
            parsed = JSON.readValue(text.substring(start, end + 1), PlanDocument.class);
        } catch (JacksonException e) {
            throw new PlanInvalidException("The plan is not valid JSON: " + e.getOriginalMessage(),
                    Map.of("plan", "not valid JSON"));
        }
        return parsed.validated();
    }

    /** Stage 1 of an engine plan: the phases, whose tasks come in stage 2. Not validated yet. */
    static PlanDocument parseSkeleton(String text) {
        PlanDocument skeleton = read(text, PlanDocument.class);
        if (skeleton.phases() == null || skeleton.phases().isEmpty()) {
            throw new PlanInvalidException("The answer has no phases", Map.of("phases", "at least one phase is required"));
        }
        return skeleton;
    }

    /** The tasks of one phase, as stage 2 answers them. */
    record Tasks(List<TaskPlan> tasks) {
    }

    /** Stage 2 of an engine plan: the tasks of one phase. */
    static List<TaskPlan> parseTasks(String text) {
        Tasks tasks = read(text, Tasks.class);
        return tasks.tasks() == null ? List.of() : tasks.tasks();
    }

    /** Stage 1 plus the tasks of each phase, validated like any other plan. */
    PlanDocument withTasks(List<List<TaskPlan>> tasksByPhase) {
        List<PhasePlan> filled = new ArrayList<>();
        for (int i = 0; i < phases.size(); i++) {
            PhasePlan p = phases.get(i);
            filled.add(new PhasePlan(p.title(), p.objective(), p.scope(), p.strategy(), p.architecture(),
                    p.prerequisites(), p.agents(), p.software(), p.risks(), p.completionCriteria(),
                    p.reviewCriteria(), tasksByPhase.get(i)));
        }
        return new PlanDocument(version, summary, stack, filled).validated();
    }

    private static <T> T read(String text, Class<T> type) {
        if (text == null || text.isBlank()) {
            throw new PlanInvalidException("The answer is empty", Map.of("plan", "empty"));
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new PlanInvalidException("No JSON object in the answer", Map.of("plan", "no JSON object found"));
        }
        try {
            return JSON.readValue(text.substring(start, end + 1), type);
        } catch (JacksonException e) {
            throw new PlanInvalidException("The answer is not valid JSON: " + e.getOriginalMessage(),
                    Map.of("plan", "not valid JSON"));
        }
    }

    public String toJson() {
        return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(this) + "\n";
    }

    public int taskCount() {
        return phases.stream().mapToInt(phase -> phase.tasks().size()).sum();
    }

    /** Every problem at once, named by path, so a model or a person can fix them in one pass. */
    PlanDocument validated() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (phases == null || phases.isEmpty()) {
            errors.put("phases", "at least one phase is required");
            throw new PlanInvalidException("The plan has no phases", errors);
        }
        if (phases.size() > MAX_PHASES) {
            errors.put("phases", "at most " + MAX_PHASES + " phases");
        }
        List<PhasePlan> normalizedPhases = new ArrayList<>();
        int total = 0;
        for (int p = 0; p < phases.size(); p++) {
            PhasePlan phase = phases.get(p);
            String at = "phases[" + p + "]";
            if (phase == null) {
                errors.put(at, "is null");
                continue;
            }
            if (blank(phase.title())) {
                errors.put(at + ".title", "is required");
            }
            List<TaskPlan> tasks = phase.tasks() == null ? List.of() : phase.tasks();
            if (tasks.isEmpty()) {
                errors.put(at + ".tasks", "at least one task is required");
            }
            if (tasks.size() > MAX_TASKS_PER_PHASE) {
                errors.put(at + ".tasks", "at most " + MAX_TASKS_PER_PHASE + " tasks per phase");
            }
            List<TaskPlan> normalizedTasks = new ArrayList<>();
            for (int t = 0; t < tasks.size(); t++) {
                TaskPlan task = tasks.get(t);
                if (task == null || blank(task.title())) {
                    errors.put(at + ".tasks[" + t + "].title", "is required");
                    continue;
                }
                normalizedTasks.add(new TaskPlan(cut(task.title(), 255), text(task.objective(), 3000),
                        text(task.why(), 2000), text(task.scope(), 3000), text(task.implementation(), 6000),
                        cut(task.agentRole(), 120), list(task.subagents()), list(task.software()),
                        list(task.files()), list(task.folders()), list(task.contracts()), list(task.tests()),
                        list(task.completionCriteria()), priority(task.priority())));
            }
            total += normalizedTasks.size();
            if (phase.title() != null) {
                normalizedPhases.add(new PhasePlan(cut(phase.title(), 200), text(phase.objective(), 3000),
                        text(phase.scope(), 3000), text(phase.strategy(), 4000), text(phase.architecture(), 4000),
                        list(phase.prerequisites()), list(phase.agents()), list(phase.software()),
                        list(phase.risks()), list(phase.completionCriteria()), list(phase.reviewCriteria()),
                        normalizedTasks));
            }
        }
        if (total > MAX_TASKS) {
            errors.put("phases", "at most " + MAX_TASKS + " tasks in a plan");
        }
        if (!errors.isEmpty()) {
            throw new PlanInvalidException("The plan does not follow the plan.json format", errors);
        }
        return new PlanDocument(1, text(summary, 4000), list(stack), normalizedPhases);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String text(String value, int max) {
        return blank(value) ? null : cut(value, max);
    }

    private static String cut(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max - 1) + "…";
    }

    private static List<String> list(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::strip).filter(v -> !v.isEmpty())
                .map(v -> cut(v, 500)).limit(30).toList();
    }

    private static String priority(String value) {
        if (value == null) {
            return "MEDIUM";
        }
        String upper = value.strip().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "HIGH", "ALTA", "CRITICAL" -> "HIGH";
            case "LOW", "BASSA" -> "LOW";
            default -> "MEDIUM";
        };
    }
}
