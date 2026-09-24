package com.aicompany.backend.plan.service;

import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.project.model.Project;

import java.util.List;

/**
 * The human-readable face of {@code plan.json}: {@code IMPLEMENTATION_PLAN.md},
 * {@code PHASE_N.md}, {@code TASK-NNN.md} (directive §10–11, ADR-020).
 *
 * <p>Every task document ends with the compact prompt that is enough for an agent
 * to start -- the whole point of moving context into files -- and with an
 * <em>Esito</em> section the agent fills in.
 */
public final class PlanMarkdown {

    /** What the importer decided for one task, rendered next to what the plan said. */
    public record TaskRef(String code, String documentPath, String agentName, PlanDocument.TaskPlan plan) {
    }

    public record PhaseRef(int number, String documentPath, PlanDocument.PhasePlan plan, List<TaskRef> tasks) {
    }

    private PlanMarkdown() {
    }

    public static String compactPrompt(String code) {
        return "Esegui " + code + " seguendo `tasks/" + code + ".md` e la governance in `AGENTS.md`.";
    }

    public static String implementationPlan(Project project, PlanDocument plan, List<PhaseRef> phases, String source) {
        StringBuilder md = new StringBuilder();
        md.append("# IMPLEMENTATION PLAN — ").append(project.getName()).append("\n\n");
        md.append("> Generato dal Master Orchestrator di AI Company OS (").append(source)
                .append(") a partire da `MASTER_PROMPT.md`. Forma canonica: `.aicos/plan.json`.\n");
        md.append("> Stato: **da approvare** — nessuna task parte finché la sua fase non è approvata.\n");
        md.append("> Human-in-the-Loop: ").append(AutonomyPolicy.label(project.getAutonomyLevel())).append("\n\n");
        section(md, "Sintesi", plan.summary());
        bullets(md, "Stack", plan.stack());
        md.append("## Fasi\n\n| Fase | Titolo | Task | Documento |\n|---|---|---|---|\n");
        for (PhaseRef phase : phases) {
            md.append("| ").append(phase.number()).append(" | ").append(cell(phase.plan().title())).append(" | ")
                    .append(phase.tasks().size()).append(" | `").append(phase.documentPath()).append("` |\n");
        }
        md.append("\n## Come si procede\n\n");
        md.append("1. L'operatore legge questo piano e le fasi, poi le approva (o chiede modifiche) dalla console.\n");
        md.append("2. Per ogni task il Master Orchestrator propone agente, modello, software e contesto.\n");
        md.append("3. La task viene eseguita dall'AI Engine o consegnata a un agente esterno con un prompt compatto.\n");
        md.append("4. L'operatore revisiona l'esito e chiude la task.\n");
        return md.toString();
    }

    public static String phase(Project project, PhaseRef phase) {
        PlanDocument.PhasePlan p = phase.plan();
        StringBuilder md = new StringBuilder();
        md.append("# PHASE ").append(phase.number()).append(" — ").append(p.title()).append("\n\n");
        md.append("> Progetto: ").append(project.getName()).append(" · Stato: PLANNED · Approvazione: PENDING\n\n");
        section(md, "Obiettivo", p.objective());
        section(md, "Scope", p.scope());
        bullets(md, "Prerequisiti", p.prerequisites());
        section(md, "Architettura rilevante", p.architecture());
        section(md, "Strategia di implementazione", p.strategy());
        md.append("## Task della fase\n\n| Codice | Titolo | Agente | Priorità |\n|---|---|---|---|\n");
        for (TaskRef task : phase.tasks()) {
            md.append("| [").append(task.code()).append("](../../").append(task.documentPath()).append(") | ")
                    .append(cell(task.plan().title())).append(" | ")
                    .append(cell(task.agentName() == null ? orDash(task.plan().agentRole()) : task.agentName()))
                    .append(" | ").append(task.plan().priority()).append(" |\n");
        }
        md.append('\n');
        bullets(md, "Agenti coinvolti", p.agents());
        bullets(md, "Software necessari", p.software());
        md.append("## File e contesto necessari\n\n- `MASTER_PROMPT.md`\n- `docs/IMPLEMENTATION_PLAN.md`\n")
                .append("- i documenti delle task elencate sopra\n- `references/` se la fase riguarda UI o grafica\n\n");
        section(md, "Debiti aperti", "Nessuno registrato alla generazione.");
        bullets(md, "Rischi", p.risks());
        bullets(md, "Criteri di completamento", p.completionCriteria());
        bullets(md, "Criteri di review", p.reviewCriteria());
        md.append("## Prompt della fase\n\n> Esegui la PHASE ").append(phase.number())
                .append(" seguendo `").append(phase.documentPath())
                .append("`: una task alla volta, nell'ordine, ciascuna col proprio file.\n");
        return md.toString();
    }

    public static String task(Project project, PhaseRef phase, TaskRef task) {
        PlanDocument.TaskPlan t = task.plan();
        StringBuilder md = new StringBuilder();
        md.append("# ").append(task.code()).append(" — ").append(t.title()).append("\n\n");
        md.append("> Progetto: ").append(project.getName()).append(" · Fase ").append(phase.number()).append(" — ")
                .append(phase.plan().title()).append(" · Stato: OPEN · Priorità: ").append(t.priority()).append("\n");
        md.append("> Agente principale: ")
                .append(task.agentName() == null ? orDash(t.agentRole()) : task.agentName() + " (" + orDash(t.agentRole()) + ")")
                .append(t.subagents().isEmpty() ? "" : " · Sottoagenti: " + String.join(", ", t.subagents()))
                .append("\n\n");
        section(md, "Obiettivo", t.objective());
        section(md, "Perché esiste", t.why());
        section(md, "Scope", t.scope());
        section(md, "Come implementarla", t.implementation());
        bullets(md, "Software e tool necessari", t.software());
        bullets(md, "File da leggere", withDefaults(t.files(), phase.documentPath()));
        bullets(md, "Cartelle rilevanti", t.folders());
        bullets(md, "Contratti, API, schema interessati", t.contracts());
        section(md, "Grafi rilevanti", "—");
        bullets(md, "Test richiesti", t.tests());
        section(md, "Debiti collegati", "—");
        bullets(md, "Criteri di completamento", t.completionCriteria());
        md.append("## Prompt compatto\n\n> ").append(compactPrompt(task.code())).append("\n\n");
        md.append("## Esito\n\n_Da compilare dall'agente al termine: cosa è stato fatto, file toccati, test eseguiti, ")
                .append("cosa resta._\n\n## Note\n\n");
        return md.toString();
    }

    private static List<String> withDefaults(List<String> files, String phaseDocument) {
        List<String> all = new java.util.ArrayList<>();
        all.add("`AGENTS.md`");
        all.add("`" + phaseDocument + "`");
        all.addAll(files);
        return all;
    }

    private static void section(StringBuilder md, String title, String body) {
        md.append("## ").append(title).append("\n\n").append(body == null || body.isBlank() ? "—" : body.strip())
                .append("\n\n");
    }

    private static void bullets(StringBuilder md, String title, List<String> items) {
        md.append("## ").append(title).append("\n\n");
        if (items == null || items.isEmpty()) {
            md.append("—\n\n");
            return;
        }
        items.forEach(item -> md.append("- ").append(item).append('\n'));
        md.append('\n');
    }

    private static String cell(String value) {
        return value == null ? "—" : value.replace("|", "\\|").replace("\n", " ");
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
