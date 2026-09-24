package com.aicompany.backend.plan.service;

import com.aicompany.backend.project.model.Project;

import java.util.List;

/**
 * What the Master Orchestrator asks a planner for -- an engine model, or an
 * external agent reading the same text from a handoff file (ADR-021 §2). One
 * text for both, so the plan's format cannot depend on who wrote it.
 */
public final class PlanPrompt {

    static final String SCHEMA = """
            {
              "summary": "2-4 frasi: cosa si costruisce e come",
              "stack": ["tecnologia", "..."],
              "phases": [
                {
                  "title": "titolo breve della fase",
                  "objective": "obiettivo verificabile della fase",
                  "scope": "cosa include e cosa no",
                  "strategy": "come si implementa, in ordine",
                  "architecture": "componenti e decisioni rilevanti",
                  "prerequisites": ["..."],
                  "agents": ["ruoli coinvolti"],
                  "software": ["chiavi del Software Hub"],
                  "risks": ["..."],
                  "completionCriteria": ["..."],
                  "reviewCriteria": ["..."],
                  "tasks": [
                    {
                      "title": "titolo imperativo della task",
                      "objective": "risultato atteso",
                      "why": "perché serve",
                      "scope": "confini",
                      "implementation": "passi concreti",
                      "agentRole": "uno dei ruoli disponibili",
                      "subagents": [],
                      "software": ["chiavi del Software Hub"],
                      "files": ["percorsi da creare o leggere"],
                      "folders": ["cartelle rilevanti"],
                      "contracts": ["API, schema, interfacce"],
                      "tests": ["test richiesti"],
                      "completionCriteria": ["criteri verificabili"],
                      "priority": "HIGH | MEDIUM | LOW"
                    }
                  ]
                }
              ]
            }""";

    /*
     * The engine plans in two stages -- the phases, then the tasks of each phase --
     * each constrained by a JSON Schema the provider enforces token by token
     * (Ollama). Measured on the operator machine (qwen3.5:9b, about 6 tokens/s):
     * the whole plan in one constrained call took more than ten minutes, while the
     * stages take about 2.5 minutes and 1.5 minutes per phase. The bounds make an
     * empty phase impossible; the maxLength values keep a small model from
     * writing essays where a line is enough.
     */

    /** Stage 1: the phases, in build order, without their tasks. */
    public static final String PHASES_SCHEMA = """
            {"type": "object",
             "properties": {
               "summary": {"type": "string", "maxLength": 400},
               "stack": {"type": "array", "items": {"type": "string", "maxLength": 60}, "minItems": 1, "maxItems": 8},
               "phases": {"type": "array", "minItems": 2, "maxItems": 5, "items": {"type": "object",
                 "properties": {
                   "title": {"type": "string", "maxLength": 90},
                   "objective": {"type": "string", "maxLength": 300},
                   "scope": {"type": "string", "maxLength": 300},
                   "strategy": {"type": "string", "maxLength": 400},
                   "completionCriteria": {"type": "array", "items": {"type": "string", "maxLength": 150},
                                          "minItems": 1, "maxItems": 3}},
                 "required": ["title", "objective", "scope", "strategy", "completionCriteria"]}}},
             "required": ["summary", "stack", "phases"]}""";

    /** Stage 2: the tasks of one phase. */
    public static final String TASKS_SCHEMA = """
            {"type": "object",
             "properties": {
               "tasks": {"type": "array", "minItems": 2, "maxItems": 5, "items": {"type": "object",
                 "properties": {
                   "title": {"type": "string", "maxLength": 90},
                   "objective": {"type": "string", "maxLength": 250},
                   "implementation": {"type": "string", "maxLength": 500},
                   "agentRole": {"type": "string", "maxLength": 60},
                   "software": {"type": "array", "items": {"type": "string", "maxLength": 40}, "maxItems": 3},
                   "files": {"type": "array", "items": {"type": "string", "maxLength": 100}, "maxItems": 5},
                   "tests": {"type": "array", "items": {"type": "string", "maxLength": 150}, "minItems": 1, "maxItems": 3},
                   "completionCriteria": {"type": "array", "items": {"type": "string", "maxLength": 150},
                                          "minItems": 1, "maxItems": 3},
                   "priority": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]}},
                 "required": ["title", "objective", "implementation", "agentRole", "tests", "completionCriteria",
                              "priority"]}}},
             "required": ["tasks"]}""";

    public static String phasesSystem() {
        return """
                You are the Master Orchestrator of AI Company OS, planning a software project.
                From the MASTER PROMPT, list the phases of the implementation plan in the order they must be built.
                The first phase sets up the project skeleton and its tests; the last covers review and release.
                Write every text value in Italian. Be concrete and brief.""";
    }

    public static String tasksSystem(List<String> agentRoles, List<String> softwareKeys) {
        return """
                You are the Master Orchestrator of AI Company OS, planning ONE phase of a software project.
                List the tasks of this phase only: each small enough for one agent session, with verifiable \
                completion criteria and the tests it needs.
                "agentRole" must be one of: %s.
                "software" entries must be keys from: %s.
                Write every text value in Italian. Be concrete and brief.""".formatted(
                String.join(", ", agentRoles), String.join(", ", softwareKeys));
    }

    public static String tasksUser(Project project, String masterPrompt, String summary, List<String> phaseTitles,
                                   int index, PlanDocument.PhasePlan phase) {
        StringBuilder all = new StringBuilder();
        for (int i = 0; i < phaseTitles.size(); i++) {
            all.append(i + 1).append(". ").append(phaseTitles.get(i)).append('\n');
        }
        return """
                %s
                Plan summary: %s
                All phases:
                %s
                THE PHASE TO DETAIL NOW: %d. %s
                Objective: %s
                Scope: %s
                Strategy: %s
                """.formatted(user(project, masterPrompt), summary == null ? "" : summary, all, index + 1,
                phase.title(), nz(phase.objective()), nz(phase.scope()), nz(phase.strategy()));
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }

    private PlanPrompt() {
    }

    public static String user(Project project, String masterPrompt) {
        return """
                Project: %s
                Project type: %s
                Proposed stack: %s

                MASTER PROMPT:
                %s
                """.formatted(project.getName(),
                project.getProjectType() == null ? "not specified" : project.getProjectType().name(),
                project.getStack() == null ? "not specified" : project.getStack(),
                masterPrompt);
    }

    /** The same request, for an external agent that reads files and writes one. */
    public static String handoff(Project project, List<String> agentRoles, List<String> softwareKeys) {
        return """
                # Handoff — pianificazione di %s

                Sei il pianificatore del progetto per AI Company OS.

                1. Leggi `MASTER_PROMPT.md` e `AGENTS.md` in questa cartella.
                2. Scrivi il piano di implementazione in `.aicos/plan.json`, esattamente con questa forma:

                ```json
                %s
                ```

                Regole:
                - da 2 a 6 fasi nell'ordine di costruzione, ciascuna con 2–6 task piccole e verificabili;
                - `agentRole` fra: %s;
                - `software` fra le chiavi: %s;
                - testi in italiano, chiavi JSON invariate.

                3. Non scrivere altri file: AI Company OS genera `docs/IMPLEMENTATION_PLAN.md`, i `PHASE_N.md` e i
                   `TASK-NNN.md` quando l'operatore preme «Importa piano».
                """.formatted(project.getName(), SCHEMA, String.join(", ", agentRoles), String.join(", ", softwareKeys));
    }
}
