package com.aicompany.backend.workspace.service;

import com.aicompany.backend.plan.service.PlanMarkdown;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 36 (ADR-042 §5, revises ADR-020 W1): the passages AI Company OS itself wrote in
 * a project's governance and task documents, found <em>verbatim</em>, brought to their
 * current version -- so that a project begun before a change of the way tasks are
 * delivered follows it too. A passage the operator changed is no longer verbatim and is
 * left as it is; nothing else in the file is touched. Pure functions: writing, and the
 * backup copy of what is replaced, are the caller's.
 */
public final class GeneratedTextUpgrades {

    /** The steps of AGENTS.md as the template wrote them until PHASE 33. */
    static final String AGENTS_STEPS_V1 = """
            1. Leggi `tasks/TASK-NNN.md`, poi la sua fase, poi solo i file che la task elenca.
            2. Resta nello scope della task. Quello che noti e non fai, scrivilo nella sezione *Note* della task.
            3. Scrivi i test richiesti dalla task e falli girare.
            4. Alla fine aggiorna la sezione *Esito* della task: cosa hai fatto, file toccati, test, cosa resta.
            5. Non dichiarare finita una task: la chiude l'operatore dopo la review (Human-in-the-Loop).
            """;

    /** The first step of AGENTS.md as PHASE 34 wrote it. */
    static final String AGENTS_STEP1_V2 = """
            1. Se in `.aicos/handoffs/` c'è il pacchetto della tua task (`TASK-NNN-<strumento>.md`), leggilo per primo:
               contiene il ruolo, le skill, le knowledge e le regole che AI Company OS ha preparato per te.
            """;

    /** The prompt of the context map until PHASE 33. */
    static final String CONTEXT_MAP_V1 = """
            Prompt tipo, che basta a un agente per partire:

            > Esegui TASK-NNN seguendo `tasks/TASK-NNN.md` e la governance in `AGENTS.md`.
            """;

    /** The paragraph PHASE 34 added to the context map. */
    static final String CONTEXT_MAP_V2 = """
            Senza consegna, `Esegui TASK-NNN seguendo tasks/TASK-NNN.md e la governance in AGENTS.md` fa partire
            l'agente con la sola governance e la task: niente ruolo, skill e knowledge dell'agente.""";

    /** The section of a task document until PHASE 33. */
    private static final Pattern TASK_PROMPT_V1 = Pattern.compile(
            "## Prompt compatto\n\n> Esegui (\\S+) seguendo `tasks/\\1\\.md` e la governance in `AGENTS\\.md`\\.\n\n");

    /** The section PHASE 34 wrote. */
    private static final Pattern TASK_DELIVERY_V2 = Pattern.compile(
            "## Come si consegna\n\nDalla console di AI Company OS: [^\n]*?`\\.aicos/handoffs/(\\S+)-<strumento>\\.md`"
                    + "[^\n]*?», incollato a mano, dà all'agente solo la governance e questo documento\\.\n\n");

    private static final String AGENTS_TEMPLATE = resource("workspace-templates/AGENTS.md");
    private static final String CONTEXT_MAP_TEMPLATE = resource("workspace-templates/CONTEXT_MAP.md");

    private GeneratedTextUpgrades() {
    }

    /**
     * The file brought to the current version, or empty when nothing in it is a passage
     * of an earlier one. Line endings are kept as the file has them.
     *
     * @param relative the file's path in the project folder, with forward slashes
     */
    public static Optional<String> upgrade(String relative, String text) {
        boolean crlf = text.contains("\r\n");
        String plain = text.replace("\r\n", "\n");
        String out;
        if (relative.equals("AGENTS.md")) {
            out = agents(plain);
        } else if (relative.equals(".aicos/CONTEXT_MAP.md")) {
            out = contextMap(plain);
        } else if (isTaskDocument(relative)) {
            out = taskDocument(plain);
        } else {
            return Optional.empty();
        }
        if (out.equals(plain)) {
            return Optional.empty();
        }
        return Optional.of(crlf ? out.replace("\n", "\r\n") : out);
    }

    /** A task's own document: {@code tasks/<CODE>.md}, not its outcome file. */
    public static boolean isTaskDocument(String relative) {
        return relative.startsWith("tasks/") && relative.endsWith(".md") && !relative.endsWith(".esito.md")
                && relative.indexOf('/', "tasks/".length()) < 0;
    }

    static String agents(String text) {
        String steps = section(AGENTS_TEMPLATE, "## Come si lavora su una task\n\n", "\n## ");
        String firstStep = steps.substring(0, steps.indexOf("\n2. ") + 1);
        return text.replace(AGENTS_STEPS_V1, steps).replace(AGENTS_STEP1_V2, firstStep);
    }

    static String contextMap(String text) {
        String prompts = CONTEXT_MAP_TEMPLATE.substring(CONTEXT_MAP_TEMPLATE.indexOf("Prompt tipo, che la consegna"))
                .replace("\r\n", "\n").strip() + "\n";
        String manual = prompts.substring(prompts.indexOf("Se avvii l'agente a mano")).strip();
        return text.replace(CONTEXT_MAP_V1, prompts).replace(CONTEXT_MAP_V2, manual);
    }

    static String taskDocument(String text) {
        String out = replaceAll(TASK_PROMPT_V1, text);
        return replaceAll(TASK_DELIVERY_V2, out);
    }

    private static String replaceAll(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(PlanMarkdown.deliverySection(m.group(1))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String section(String text, String start, String end) {
        String plain = text.replace("\r\n", "\n");
        int from = plain.indexOf(start) + start.length();
        int to = plain.indexOf(end, from);
        return plain.substring(from, to).strip() + "\n";
    }

    private static String resource(String name) {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(name + " is unreadable", e);
        }
    }
}
