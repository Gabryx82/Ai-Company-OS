package com.aicompany.backend.plan.service;

import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.project.model.Project;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The human-readable face of {@code plan.json}: {@code IMPLEMENTATION_PLAN.md},
 * {@code PHASE_N.md}, {@code TASK-NNN.md} (directive §10–11, ADR-020).
 *
 * <p>Every task document ends with how it is delivered and with an <em>Esito</em>
 * section: a summary the agent writes, with the link to the full outcome in
 * {@code TASK-NNN.esito.md} beside it (PHASE 35, ADR-041 §10).
 */
public final class PlanMarkdown {

    /** What the importer decided for one task, rendered next to what the plan said. */
    public record TaskRef(@Nullable String code, String documentPath, String agentName, PlanDocument.TaskPlan plan) {
    }

    public record PhaseRef(int number, String documentPath, PlanDocument.PhasePlan plan, List<TaskRef> tasks) {
    }

    private PlanMarkdown() {
    }

    /**
     * PHASE 35 (ADR-041 §10): where the full outcome of a task goes -- a file beside its
     * document, {@code tasks/TASK-001.md} → {@code tasks/TASK-001.esito.md}. The document's
     * Esito section keeps a summary and the link.
     */
    public static String outcomePath(String documentPath) {
        return (documentPath.endsWith(".md") ? documentPath.substring(0, documentPath.length() - 3) : documentPath)
                + ".esito.md";
    }

    /**
     * The sections of a task document an engine run carries (ADR-041 §10): what the work is and how the plan
     * says to do and check it. Not why it exists, the tools, the delivery, the notes or the outcome.
     */
    private static final java.util.Set<String> FOR_RUN = java.util.Set.of("obiettivo", "scope", "come implementarla",
            "contratti, api, schema interessati", "file da leggere", "test richiesti", "criteri di completamento");

    /** Of those, the guidance a run gets lighter: item by item, without the why, within a limit. */
    private static final java.util.Set<String> COMPACTED = java.util.Set.of("come implementarla", "test richiesti",
            "criteri di completamento");

    /** The default limit of each compacted section, in characters ({@code aicos.runs.task-section-chars}). */
    public static final int SECTION_CHARS = 800;

    private static final java.util.regex.Pattern LIST_ITEM = java.util.regex.Pattern.compile("^\\s*([-*]|\\d+[.)])\\s+");
    private static final java.util.regex.Pattern SENTENCE_END = java.util.regex.Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}0-9`])");
    private static final java.util.regex.Pattern WHY = java.util.regex.Pattern.compile(
            "[,;]\\s+(perché|poiché|because|since)\\s.*$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern SECTION = java.util.regex.Pattern.compile("(?m)^##[ \\t]+(.+?)[ \\t]*$");
    private static final java.util.regex.Pattern TITLE = java.util.regex.Pattern.compile("(?m)\\A\\s*(#[ \\t][^\\n]*)");

    /**
     * PHASE 35 (ADR-041 §10): of a task document, what an engine run reads -- its title
     * and its working sections ({@link #FOR_RUN}) in their order; not the delivery, the
     * notes, and above all not the Esito, whose history grows with every round of work.
     * A document without any of those sections (one written by hand) is read up to its
     * Esito.
     */
    public static String forRun(String document) {
        return forRun(document, SECTION_CHARS);
    }

    /**
     * As {@link #forRun(String)}, with {@code sectionChars} the limit of each guidance
     * section -- how to implement it, the tests, the completion criteria -- which are
     * compacted ({@link #compact}); the other sections come whole.
     */
    public static String forRun(String document, int sectionChars) {
        String text = document.replace("\r\n", "\n");
        java.util.regex.Matcher heading = SECTION.matcher(text);
        java.util.List<int[]> sections = new java.util.ArrayList<>();
        java.util.List<String> titles = new java.util.ArrayList<>();
        while (heading.find()) {
            sections.add(new int[] {heading.start(), 0});
            titles.add(heading.group(1).strip().toLowerCase(java.util.Locale.ROOT));
        }
        StringBuilder kept = new StringBuilder();
        for (int i = 0; i < sections.size(); i++) {
            if (FOR_RUN.contains(titles.get(i))) {
                int end = i + 1 < sections.size() ? sections.get(i + 1)[0] : text.length();
                String section = text.substring(sections.get(i)[0], end).strip();
                String body = section.contains("\n") ? section.substring(section.indexOf('\n')).strip() : "";
                if (body.isEmpty() || body.equals("—")) {
                    continue; // nothing in it: what the plan writes for a value it does not have
                }
                if (COMPACTED.contains(titles.get(i))) {
                    section = section.substring(0, section.indexOf('\n')) + "\n\n" + compact(body, sectionChars);
                }
                kept.append("\n\n").append(section);
            }
        }
        if (kept.isEmpty()) {
            int esito = titles.indexOf("esito");
            return (esito < 0 ? text : text.substring(0, sections.get(esito)[0])).strip();
        }
        java.util.regex.Matcher title = TITLE.matcher(text);
        String head = title.find() && (sections.isEmpty() || title.start(1) < sections.get(0)[0]) ? title.group(1).strip() : "";
        return (head + kept).strip();
    }

    /**
     * PHASE 35 (ADR-041 §10): a guidance section, lighter. Its items -- the lines of a list,
     * or the sentences of a paragraph -- each lose their causal explanation ({@code ", perché
     * ..."}, {@code ", because ..."}: the instruction stays, the why goes); then, over
     * {@code limit} characters, the items that fit are kept in order -- the first one always,
     * an item is never split -- and the cut is marked.
     */
    static String compact(String body, int limit) {
        boolean list = body.lines().anyMatch(line -> LIST_ITEM.matcher(line).find());
        java.util.List<String> items = new java.util.ArrayList<>();
        if (list) {
            for (String line : body.split("\n")) {
                if (LIST_ITEM.matcher(line).find() || items.isEmpty()) {
                    items.add(line.strip());
                } else if (!line.isBlank()) {
                    items.set(items.size() - 1, items.getLast() + " " + line.strip());
                }
            }
        } else {
            items.addAll(java.util.List.of(SENTENCE_END.split(body.replaceAll("\\s+", " ").strip())));
        }
        StringBuilder out = new StringBuilder();
        boolean cut = false;
        for (String item : items) {
            String light = withoutWhy(item);
            String separator = out.isEmpty() ? "" : list ? "\n" : " ";
            if (!out.isEmpty() && out.length() + separator.length() + light.length() > limit) {
                cut = true;
                break;
            }
            out.append(separator).append(light);
        }
        return cut ? out + "\n[…]" : out.toString();
    }

    private static String withoutWhy(String item) {
        java.util.regex.Matcher why = WHY.matcher(item);
        if (!why.find()) {
            return item;
        }
        String kept = item.substring(0, why.start()).stripTrailing();
        return item.stripTrailing().endsWith(".") ? kept + "." : kept;
    }

    /**
     * PHASE 36: the prompt shown for a task -- in its document, in the panel, in the context
     * map -- leads to its handoff package, the one way the agent gets its role, skills and
     * knowledge; when the task was never delivered, the agent asks for the delivery
     * instead of starting without them.
     */
    public static String compactPrompt(String code) {
        return "Esegui " + code + " partendo dal suo pacchetto di consegna, il più recente `.aicos/handoffs/" + code
                + "-*.md` (ruolo, skill, knowledge e regole); se non c'è, chiedi all'operatore di consegnare la task da "
                + "AI Company OS prima di cominciare.";
    }

    /** PHASE 36: the delivery section of a task document, as the plan writes it and an upgrade restores it. */
    public static String deliverySection(String code) {
        return "## Come si consegna\n\n"
                + "Dalla console di AI Company OS: task → Master Orchestrator → strumento → «Prepara e apri». La consegna "
                + "controlla che sia tutto pronto, scrive `.aicos/handoffs/" + code + "-<strumento>.md` con ruolo, skill, "
                + "knowledge e regole dell'agente, e apre lo strumento con il prompt che lo legge. Se avvii l'agente a mano, "
                + "dagli questo prompt: lo porta al pacchetto, e se la task non è ancora stata consegnata gli fa chiedere "
                + "la consegna.\n\n> " + compactPrompt(code) + "\n\n";
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
        // PHASE 34: the way in is the handoff, which gives the agent its role, skills and knowledge;
        // PHASE 36: and the prompt shown here leads there too.
        md.append(deliverySection(task.code()));
        // PHASE 35 (ADR-041 §10): the full outcome in its own file; here a summary and the link.
        String outcome = outcomePath(task.documentPath()).substring(outcomePath(task.documentPath()).lastIndexOf('/') + 1);
        md.append("## Esito\n\n_Da compilare dall'agente al termine: un riassunto di poche righe e il link a [`")
                .append(outcome).append("`](").append(outcome).append("), dove va l'esito completo (cosa è stato fatto, ")
                .append("file toccati, test eseguiti, cosa resta)._\n\n## Note\n\n");
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
