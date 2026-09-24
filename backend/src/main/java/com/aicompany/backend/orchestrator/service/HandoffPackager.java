package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.binding.AgentBindingService;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.project.model.AutonomyLevel;
import com.aicompany.backend.project.model.Project;
import com.aicompany.backend.task.model.Task;

import java.nio.file.Path;
import java.util.List;

/**
 * The text of a handoff (ADR-021 §5, extended by ADR-025 §4): everything an
 * application or a CLI needs to do the task without an API between it and AI
 * Company OS -- the working folder, the task, the role the agent plays, its
 * skills and tools, the files to read, the Human-in-the-Loop rules, and what to
 * leave behind. Pure functions of their inputs; writing and launching are the
 * caller's.
 */
final class HandoffPackager {

    /** Files this packager writes carry it; a file without it belongs to the operator and is never overwritten. */
    static final String MARKER = "<!-- generato da AI Company OS: si rigenera a ogni handoff -->";

    private HandoffPackager() {
    }

    /** The label of the task in file names: its plan code, or TASK-&lt;id&gt; outside a plan. */
    static String label(Task task) {
        return task.getCode() != null ? task.getCode() : "TASK-" + task.getId();
    }

    /** The task document of a task outside a plan: what the plan would have written for it. */
    static String adHocTaskDocument(Task task, Project project, Agent agent) {
        return """
                # %s — %s

                > Task creata fuori da un piano; documento preparato da AI Company OS per l'handoff.

                - **Progetto**: %s
                - **Priorità**: %s
                - **Agente**: %s (%s)

                ## Obiettivo

                %s

                ## Esito

                _Da compilare a fine lavoro: cosa è stato fatto, file toccati, test eseguiti, cosa resta._
                """.formatted(label(task), task.getTitle(), project == null ? "nessuno" : project.getName(),
                task.getPriority(), agent.getName(), agent.getRole(),
                task.getDescription() == null || task.getDescription().isBlank() ? task.getTitle() : task.getDescription());
    }

    record Package(String document, String compactPrompt, String fullPrompt) {
    }

    static Package build(Task task, Project project, Agent agent, List<HarnessResource> harness,
                         AgentBindingService.Binding binding, ExecutionTarget target, Path folder,
                         String taskDocument, String taskDocumentText, String packagePath) {
        AutonomyLevel level = project == null ? AutonomyLevel.GUIDED : project.getAutonomyLevel();
        String role = role(agent);
        String skills = skills(harness);
        String context = context(task, project, taskDocument);
        String compact = "Leggi " + packagePath + " ed esegui la task che descrive.";

        String document = """
                # Handoff — %s → %s

                > Preparato dal Master Orchestrator di AI Company OS%s.
                > Nessuna API tra AI Company OS e %s: il lavoro passa da questa cartella e da questo file.

                ## Dove

                Cartella di lavoro: `%s`

                ## Prompt

                %s

                ## Chi sei

                %s

                ## Con che cosa

                - **Modello**: %s
                - **Provider**: %s
                - **Execution target**: %s — %s

                ## Skill, knowledge, tool e MCP

                %s

                ## Contesto da leggere, in quest'ordine

                %s

                ## Livello di Human-in-the-Loop: %s

                %s

                ## Alla fine

                - Compila la sezione **Esito** di `%s`: cosa hai fatto, file toccati, test eseguiti, cosa resta.
                - Non considerare chiusa la task: la chiude l'operatore dopo la review in AI Company OS.
                """.formatted(label(task), target.name(), project == null ? "" : " per il progetto «" + project.getName() + "»",
                target.name(), folder, compact, role,
                binding.model() == null ? "scelto nell'app o nella CLI" : binding.model().displayName() + " (`" + binding.model().key() + "`)",
                binding.provider() == null ? "—" : binding.provider().name(),
                target.name(), target.howItWorks(), skills, context, AutonomyPolicy.label(level), AutonomyPolicy.rules(level),
                taskDocument);

        String full = """
                Sei %s (%s) e lavori per AI Company OS%s, nella cartella `%s`.

                %s

                ## La task

                %s

                ## Regole di Human-in-the-Loop (%s)

                %s

                ## Skill e strumenti

                %s

                Alla fine scrivi l'esito: cosa hai fatto, file toccati, test eseguiti, cosa resta. Non chiudere tu la task.
                """.formatted(agent.getName(), agent.getRole(), project == null ? "" : ", progetto «" + project.getName() + "»",
                folder, role, taskDocumentText == null ? task.getTitle() : taskDocumentText.strip(),
                AutonomyPolicy.label(level), AutonomyPolicy.rules(level), skills);
        return new Package(document, compact, full);
    }

    /** A target's own rules file (Junie, Continue): points to AGENTS.md and to the current package. */
    static String targetRules(ExecutionTarget target, String packagePath) {
        return MARKER + "\n\n# Regole per " + target.name() + "\n\n"
                + "- La governance del progetto è in `AGENTS.md`: leggila prima di tutto.\n"
                + "- Il lavoro corrente è descritto in `" + packagePath + "`.\n"
                + "- Non chiudere le task: le chiude l'operatore in AI Company OS dopo la review.\n";
    }

    /** AGENTS.md and CLAUDE.md of an inbox folder, where no project governance exists. */
    static String inboxGovernance(String packagePath) {
        return MARKER + "\n\n# Governance\n\n"
                + "Questa cartella è stata preparata da AI Company OS per una sola task, senza progetto.\n\n"
                + "- Il lavoro da fare è in `" + packagePath + "`.\n"
                + "- Lavora solo in questa cartella; non eseguire azioni esterne senza consenso.\n"
                + "- A fine lavoro scrivi l'esito in `TASK.md`; la task la chiude l'operatore.\n";
    }

    private static String role(Agent agent) {
        StringBuilder text = new StringBuilder();
        text.append("**").append(agent.getName()).append("** — ").append(agent.getRole());
        if (agent.getSpecialization() != null) {
            text.append(", ").append(agent.getSpecialization());
        }
        text.append("\n");
        append(text, "Descrizione", agent.getDescription());
        append(text, "Istruzioni di ruolo", agent.getSystemPrompt());
        append(text, "Responsabilità", agent.getResponsibilities());
        append(text, "Direttive", agent.getDirectives());
        append(text, "Limiti", agent.getLimits());
        append(text, "Output atteso", agent.getOutputFormat());
        append(text, "Politica di contesto", agent.getContextPolicy());
        return text.toString().strip();
    }

    private static void append(StringBuilder text, String title, String value) {
        if (value != null && !value.isBlank()) {
            text.append("\n**").append(title).append("**\n\n").append(value.strip()).append("\n");
        }
    }

    private static String skills(List<HarnessResource> harness) {
        if (harness.isEmpty()) {
            return "Nessuna risorsa associata all'agente.";
        }
        StringBuilder text = new StringBuilder();
        for (HarnessResource r : harness) {
            text.append("- **").append(r.getKind()).append("** ").append(r.getName());
            if (r.getDescription() != null) {
                text.append(" — ").append(r.getDescription());
            }
            text.append("\n");
            if (r.getConfiguration() != null && !r.getConfiguration().isBlank()) {
                text.append("\n```\n").append(r.getConfiguration().strip()).append("\n```\n\n");
            }
        }
        return text.toString().strip();
    }

    private static String context(Task task, Project project, String taskDocument) {
        StringBuilder text = new StringBuilder();
        int n = 1;
        if (project != null) {
            text.append(n++).append(". `AGENTS.md` — la governance del progetto\n");
        }
        text.append(n++).append(". `").append(taskDocument).append("` — la task\n");
        if (task.getPhase() != null) {
            text.append(n++).append(". `").append(task.getPhase().getDocumentPath()).append("` — la fase a cui appartiene\n");
        }
        if (project != null) {
            text.append(n++).append(". `.aicos/CONTEXT_MAP.md` — dove si trova il resto, solo se serve\n");
        }
        text.append(n).append(". solo i file che la task nomina");
        return text.toString();
    }
}
