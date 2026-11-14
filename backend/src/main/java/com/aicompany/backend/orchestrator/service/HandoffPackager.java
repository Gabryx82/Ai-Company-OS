package com.aicompany.backend.orchestrator.service;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.binding.AgentBindingService;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import com.aicompany.backend.harness.hub.SkillHubService;
import com.aicompany.backend.harness.library.SkillLibrary;
import com.aicompany.backend.harness.model.HarnessResource;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.hitl.AutonomyPolicy;
import com.aicompany.backend.plan.service.PlanMarkdown;
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

                _Da compilare a fine lavoro: un riassunto di poche righe e il link a [`%s`](%s), dove va l'esito completo \
                (cosa è stato fatto, file toccati, test eseguiti, cosa resta)._
                """.formatted(label(task), task.getTitle(), project == null ? "nessuno" : project.getName(),
                task.getPriority(), agent.getName(), agent.getRole(),
                task.getDescription() == null || task.getDescription().isBlank() ? task.getTitle() : task.getDescription(),
                outcomeName(project, task), outcomeName(project, task));
    }

    record Package(String document, String compactPrompt, String fullPrompt) {
    }

    /**
     * @param parent   PHASE 36: the agent this one is a sub-agent of, or null
     * @param harness  the agent's own resources
     * @param adopted  PHASE 34: the resources the project adopted that the agent does not
     *                 already have -- they hold for every agent working on the project
     * @param software PHASE 36: the software the agent may use
     * @param binding  PHASE 36: the agent's model described on <em>this</em> target -- a model
     *                 the target cannot use is not named to it
     */
    static Package build(Task task, Project project, Agent agent, Agent parent, List<HarnessResource> harness,
                         List<HarnessResource> adopted, List<Software> software,
                         AgentBindingService.Binding binding, ExecutionTarget target, Path folder,
                         String taskDocument, String taskDocumentText, String packagePath,
                         java.util.Map<String, LibraryUse> uses, String mcpNote,
                         com.aicompany.backend.hitl.LearningMode learning) {
        AutonomyLevel level = project == null ? AutonomyLevel.GUIDED : project.getAutonomyLevel();
        // PHASE 37 (ADR-044): the lesson this handoff asks for, and where it goes.
        String lessonPath = com.aicompany.backend.hitl.LessonFormat.pathFor(label(task), project != null);
        String lesson = com.aicompany.backend.hitl.LessonFormat.instructions(learning, lessonPath);
        String rules = AutonomyPolicy.rules(level, learning);
        String role = role(agent, parent);
        boolean modelFits = binding.model() != null && binding.problems().isEmpty();
        String allowed = software.isEmpty() ? "nessuno indicato"
                : String.join(", ", software.stream().map(Software::getName).toList());
        String skills = skills(harness, uses);
        if (!adopted.isEmpty()) {
            skills += "\n\n**Adottate dal progetto** — valgono per ogni agente che lavora qui:\n\n" + skills(adopted, uses);
        }
        skills += mcpNote;
        String context = context(task, project, taskDocument);
        // PHASE 37 (ADR-043): an agent that does not work in the folder is told where the folder is.
        String compact = target.worksOutsideTheFolder()
                ? "Lavora nella cartella " + folder + ": leggi lì il file " + packagePath + " ed esegui la task che descrive."
                : "Leggi " + packagePath + " ed esegui la task che descrive.";
        // PHASE 35 (ADR-041 §10): the full outcome in a file beside the task document.
        String outcome = PlanMarkdown.outcomePath(taskDocument);
        String outcomeFile = outcome.substring(outcome.lastIndexOf('/') + 1);
        // PHASE 37 (ADR-047): the diary of the steps, read by the 3D office while the agent works.
        String diary = PlanMarkdown.diaryPath(taskDocument);

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
                - **Software che puoi usare**: %s

                ## Skill, knowledge, tool e MCP

                %s

                ## Contesto da leggere, in quest'ordine

                %s

                ## Livello di Human-in-the-Loop: %s

                %s
                %s%s
                ## Alla fine

                - Scrivi l'esito completo in `%s`: cosa hai fatto, file toccati, test eseguiti, cosa resta.
                - Nella sezione **Esito** di `%s` lascia solo un riassunto di poche righe e il link a quel file \
                (`[%s](%s)`).
                - Non considerare chiusa la task: la chiude l'operatore dopo la review in AI Company OS.
                """.formatted(label(task), target.name(), project == null ? "" : " per il progetto «" + project.getName() + "»",
                target.name(), folder, compact, role,
                modelFits ? binding.model().displayName() + " (`" + binding.model().key() + "`)" : "scelto nell'app o nella CLI",
                modelFits && binding.provider() != null ? binding.provider().name() : "—",
                target.name(), target.howItWorks(), allowed, skills, context, AutonomyPolicy.label(level),
                rules, lesson.isEmpty() ? "" : "\n## Lezione\n\n" + lesson, diaryRules(diary, taskDocument, outcome),
                outcome, taskDocument, outcomeFile, outcomeFile);

        String full = """
                Sei %s (%s) e lavori per AI Company OS%s, nella cartella `%s`.

                %s

                ## La task

                %s

                ## Regole di Human-in-the-Loop (%s)

                %s

                ## Skill e strumenti

                %s

                Software che puoi usare: %s.
                %s
                Mentre lavori, appena inizi e poi a ogni passo, aggiungi in fondo a `%s` una riga \
                `- <ora del sistema, con date -Iseconds o Get-Date -Format o> · <cosa hai fatto>`: AI Company OS la mostra \
                nell'Ufficio 3D. Non cancellare le righe già scritte.

                Alla fine scrivi l'esito completo in `%s` (cosa hai fatto, file toccati, test eseguiti, cosa resta) e \
                nella sezione Esito di `%s` un riassunto di poche righe con il link a quel file. Non chiudere tu la task.
                """.formatted(agent.getName(), agent.getRole(), project == null ? "" : ", progetto «" + project.getName() + "»",
                folder, role, taskDocumentText == null ? task.getTitle() : taskDocumentText.strip(),
                AutonomyPolicy.label(level), rules, skills, allowed,
                lesson.isEmpty() ? "" : "\n## Lezione\n\n" + lesson, diary, outcome, taskDocument);
        return new Package(document, compact, full);
    }

    /**
     * PHASE 37 (ADR-047): how the agent keeps its diary -- a line per step, appended, with the time
     * from the system clock -- so that the 3D office shows the work while it happens.
     */
    static String diaryRules(String diary, String taskDocument, String outcome) {
        return """

                ## Mentre lavori: il diario

                AI Company OS mostra il tuo lavoro passo passo nell'Ufficio 3D leggendo `%s`: tienilo aggiornato \
                mentre lavori, non alla fine.

                - Appena inizi, e poi a ogni passo (un file letto, una decisione, un file creato o modificato, un comando \
                o un test eseguito, un problema), **aggiungi in fondo** una riga `- <ora> · <cosa hai fatto>`, breve e al passato.
                - Prendi l'ora dal sistema, con il fuso: `date -Iseconds`, in PowerShell `Get-Date -Format o`. Per esempio \
                `- 2026-11-13T12:04:05+01:00 · Letto %s`.
                - Non cancellare né riscrivere le righe già scritte. L'ultima, a fine lavoro: `- <ora> · Finito: esito in %s`.
                """.formatted(diary, taskDocument, outcome);
    }

    /** A target's own rules file (Junie, Continue): points to AGENTS.md and to the current package. */
    static String targetRules(ExecutionTarget target, String packagePath) {
        return MARKER + "\n\n# Regole per " + target.name() + "\n\n"
                + "- La governance del progetto è in `AGENTS.md`: leggila prima di tutto.\n"
                + "- Il lavoro corrente è descritto in `" + packagePath + "`.\n"
                + "- Mentre lavori tieni il diario dei passi che il pacchetto indica: una riga per passo, aggiunta in fondo.\n"
                + "- Non chiudere le task: le chiude l'operatore in AI Company OS dopo la review.\n";
    }

    /** AGENTS.md and CLAUDE.md of an inbox folder, where no project governance exists. */
    static String inboxGovernance(String packagePath) {
        return MARKER + "\n\n# Governance\n\n"
                + "Questa cartella è stata preparata da AI Company OS per una sola task, senza progetto.\n\n"
                + "- Il lavoro da fare è in `" + packagePath + "`.\n"
                + "- Lavora solo in questa cartella; non eseguire azioni esterne senza consenso.\n"
                + "- Mentre lavori tieni il diario in `TASK.diario.md`: una riga per passo, `- <ora> · <cosa hai fatto>`, "
                + "aggiunta in fondo, con l'ora del sistema.\n"
                + "- A fine lavoro scrivi l'esito completo in `TASK.esito.md` e, nella sezione Esito di `TASK.md`, "
                + "un riassunto di poche righe con il link a quel file; la task la chiude l'operatore.\n";
    }

    /** PHASE 35: the outcome file of an ad hoc task document, as its Esito section links it. */
    private static String outcomeName(Project project, Task task) {
        return project == null ? "TASK.esito.md" : label(task) + ".esito.md";
    }

    /** PHASE 36: every field of the agent's page, in the order the page shows them. */
    private static String role(Agent agent, Agent parent) {
        StringBuilder text = new StringBuilder();
        text.append("**").append(agent.getName()).append("** — ").append(agent.getRole());
        if (agent.getSpecialization() != null) {
            text.append(", ").append(agent.getSpecialization());
        }
        text.append("\n");
        if (parent != null) {
            text.append("\nSei un sottoagente di **").append(parent.getName()).append("** (").append(parent.getRole())
                    .append("): lavori sulla parte della task che ti affida e rispondi a lui.\n");
        }
        append(text, "Descrizione", agent.getDescription());
        append(text, "Capability", agent.getCapabilities());
        append(text, "Dominio", agent.getDomain());
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

    private static String skills(List<HarnessResource> harness, java.util.Map<String, LibraryUse> uses) {
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
            if (uses.containsKey(r.getKey())) {
                text.append("  - ").append(uses.get(r.getKey()).instruction(r)).append("\n");
            }
            if (r.getConfiguration() != null && !r.getConfiguration().isBlank()) {
                text.append("\n```\n").append(r.getConfiguration().strip()).append("\n```\n\n");
            }
        }
        return text.toString().strip();
    }

    private static String context(Task task, Project project, String taskDocument) {
        StringBuilder text = new StringBuilder();
        int n = 1;
        for (Read read : contextReads(task, project, taskDocument)) {
            text.append(n++).append(". `").append(read.path()).append("` — ").append(read.kind().purpose).append('\n');
        }
        text.append(n).append(". solo i file che la task nomina");
        return text.toString();
    }

    // --- PHASE 35 (ADR-041): what the package asks the agent to read, for its measure ----------

    /** What a file the package names is to the agent. */
    enum ReadKind {
        GOVERNANCE("la governance del progetto"),
        TASK("la task"),
        PHASE("la fase a cui appartiene"),
        CONTEXT_MAP("dove si trova il resto, solo se serve"),
        SKILL(null),
        KNOWLEDGE(null);

        /** How the context section describes it; null for what the skill section names. */
        final String purpose;

        ReadKind(String purpose) {
            this.purpose = purpose;
        }
    }

    /**
     * One file the package asks the agent to read. {@code path} is relative to the
     * working folder, or absolute for a library file; {@code required} is false for
     * what the package says to read only if needed.
     */
    record Read(ReadKind kind, String path, boolean required) {
    }

    /** The context section's files, in its order -- the one list both the text and the measure come from. */
    static List<Read> contextReads(Task task, Project project, String taskDocument) {
        List<Read> reads = new java.util.ArrayList<>();
        if (project != null) {
            reads.add(new Read(ReadKind.GOVERNANCE, "AGENTS.md", true));
        }
        reads.add(new Read(ReadKind.TASK, taskDocument, true));
        if (task.getPhase() != null) {
            reads.add(new Read(ReadKind.PHASE, task.getPhase().getDocumentPath(), true));
        }
        if (project != null) {
            reads.add(new Read(ReadKind.CONTEXT_MAP, ".aicos/CONTEXT_MAP.md", false));
        }
        return reads;
    }

    /**
     * Everything the package asks the agent to read: the context section's files, then
     * the library file of each skill and knowledge entry the skill section gives it --
     * the agent's, then the project's -- as {@link #skills} lists them: required when the
     * package says to read and apply it, optional when it says to read it only if it is
     * pertinent, absent when the tool loads the skill by itself.
     */
    static List<Read> reads(Task task, Project project, String taskDocument, List<HarnessResource> resources,
                            java.util.Map<String, LibraryUse> uses) {
        List<Read> reads = new java.util.ArrayList<>(contextReads(task, project, taskDocument));
        for (HarnessResource r : resources) {
            LibraryUse use = uses.get(r.getKey());
            if (use != null && use.asksToRead()) {
                reads.add(new Read(r.getKind() == HarnessResource.Kind.SKILL ? ReadKind.SKILL : ReadKind.KNOWLEDGE,
                        use.libraryPath(), use.copy() == null && use.pertinent()));
            }
        }
        return reads;
    }

    // --- PHASE 35 (ADR-041 §8): skills the tool loads by itself ---------------------------------

    /**
     * How the package gives the agent one library entry. Without {@code copy} the file is
     * to be read and applied, as it always was. With one -- the tool of the handoff loads
     * skills by itself, and the Knowledge Hub said where its copy is -- a usable copy is
     * named for the tool to load when it is pertinent, and anything else (missing, behind
     * the library, a folder of the operator's own under that name) is said, with the copy
     * proposed and the library file to read meanwhile, if pertinent.
     */
    record LibraryUse(String libraryPath, SkillHubService.@org.jspecify.annotations.Nullable ToolCopy copy,
                      boolean pertinent, SkillLibrary.FileCheck.Status file, @org.jspecify.annotations.Nullable String problem) {

        /** As PHASE 35 built it: a valid file, pertinent. */
        LibraryUse(String libraryPath, SkillHubService.@org.jspecify.annotations.Nullable ToolCopy copy) {
            this(libraryPath, copy, true, SkillLibrary.FileCheck.Status.OK, null);
        }

        /** Whether the package asks the agent to open the library file at all. */
        boolean asksToRead() {
            return file != SkillLibrary.FileCheck.Status.MISSING && (copy == null || !copy.usable());
        }

        String instruction(HarnessResource r) {
            // PHASE 36: an entry without its file, or with a file the library cannot read, is said -- never dropped.
            if (file == SkillLibrary.FileCheck.Status.MISSING) {
                return "⚠ nessun file nella libreria (`" + libraryPath + "`): di questa voce hai solo la descrizione qui "
                        + "sopra. L'operatore lo crea dal Knowledge Hub";
            }
            if (file == SkillLibrary.FileCheck.Status.INVALID) {
                return "⚠ il file `" + libraryPath + "` non è un documento valido della libreria (" + problem
                        + "): leggilo com'è e applicalo";
            }
            if (!pertinent) {
                // PHASE 36: knowledge not pertinent to the task is named, to read only if the task needs it.
                return "consultala solo se la task la richiede: `" + libraryPath + "`";
            }
            if (copy == null) {
                return "istruzioni nel file `" + libraryPath + "`: leggilo e applicalo";
            }
            String meanwhile = "Intanto, se è pertinente, leggi `" + libraryPath + "`";
            return switch (copy.status()) {
                case "IN_SYNC" -> "usa la skill `" + copy.name() + "` se pertinente: " + copy.appName()
                        + " la carica dalla sua cartella (`" + copy.folder() + "`)";
                case "MODIFIED" -> "usa la skill `" + copy.name() + "` se pertinente: " + copy.appName()
                        + " la carica dalla sua cartella (`" + copy.folder() + "`), nella versione modificata nell'app";
                case "NONE" -> "non è nella cartella di " + copy.appName() + ": copiala dal Knowledge Hub (Libreria → "
                        + r.getName() + " → Nelle app → " + copy.appName() + "). " + meanwhile;
                case "FOREIGN" -> "in " + copy.appName() + " c'è una skill `" + r.getKey()
                        + "` che non viene dal Knowledge Hub (`" + copy.folder() + "`): resta com'è. " + meanwhile;
                default -> "la copia in " + copy.appName() + " (`" + copy.folder()
                        + "`) non è allineata alla libreria: aggiornala dal Knowledge Hub. " + meanwhile;
            };
        }
    }

    /** Characters, as the operator counts them: code points, not UTF-16 units. */
    static int chars(String text) {
        return text.codePointCount(0, text.length());
    }
}
