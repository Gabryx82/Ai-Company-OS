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

    /**
     * The same shape as {@link #SCHEMA}, as a JSON Schema for constrained
     * decoding. Leaner on purpose: the fields a small local model must produce
     * are required, the others are allowed and optional, and the sizes are
     * bounded -- 2 to 6 phases, 2 to 6 tasks each -- so the grammar itself makes
     * an empty phase impossible (measured: without it, a 9B model returned one).
     */
    public static final String JSON_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "summary": {"type": "string"},
                "stack": {"type": "array", "items": {"type": "string"}, "maxItems": 10},
                "phases": {
                  "type": "array", "minItems": 2, "maxItems": 6,
                  "items": {
                    "type": "object",
                    "properties": {
                      "title": {"type": "string"},
                      "objective": {"type": "string"},
                      "scope": {"type": "string"},
                      "strategy": {"type": "string"},
                      "software": {"type": "array", "items": {"type": "string"}, "maxItems": 6},
                      "risks": {"type": "array", "items": {"type": "string"}, "maxItems": 5},
                      "completionCriteria": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 5},
                      "tasks": {
                        "type": "array", "minItems": 2, "maxItems": 6,
                        "items": {
                          "type": "object",
                          "properties": {
                            "title": {"type": "string"},
                            "objective": {"type": "string"},
                            "implementation": {"type": "string"},
                            "agentRole": {"type": "string"},
                            "software": {"type": "array", "items": {"type": "string"}, "maxItems": 4},
                            "files": {"type": "array", "items": {"type": "string"}, "maxItems": 8},
                            "tests": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 5},
                            "completionCriteria": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 5},
                            "priority": {"type": "string", "enum": ["HIGH", "MEDIUM", "LOW"]}
                          },
                          "required": ["title", "objective", "implementation", "agentRole", "tests",
                                       "completionCriteria", "priority"]
                        }
                      }
                    },
                    "required": ["title", "objective", "scope", "strategy", "completionCriteria", "tasks"]
                  }
                }
              },
              "required": ["summary", "stack", "phases"]
            }""";

    private PlanPrompt() {
    }

    public static String system(List<String> agentRoles, List<String> softwareKeys) {
        return """
                You are the Master Orchestrator of AI Company OS, acting as the project planner.
                From the MASTER PROMPT you receive, produce the implementation plan of the project as ONE JSON \
                object and nothing else: no Markdown, no comments, no text before or after it.

                The JSON object has exactly this shape:
                %s

                Rules:
                - 2 to 6 phases, in the order they must be built; each phase has 2 to 6 tasks.
                - Every task is small enough for one agent session and has verifiable completion criteria.
                - The first phase sets up the project skeleton and its tests; the last one covers review and release.
                - "agentRole" must be one of: %s.
                - "software" entries must be keys from: %s.
                - Write every text value in Italian. Keys stay exactly as in the shape above.
                """.formatted(SCHEMA, String.join(", ", agentRoles), String.join(", ", softwareKeys));
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
