package com.aicompany.backend.hitl.lesson;

import com.aicompany.backend.hitl.lesson.Lesson.Concept;
import com.aicompany.backend.hitl.lesson.Lesson.Exercise;
import com.aicompany.backend.hitl.lesson.Lesson.Kind;
import com.aicompany.backend.hitl.lesson.Lesson.Line;
import com.aicompany.backend.hitl.lesson.Lesson.Option;
import com.aicompany.backend.hitl.lesson.Lesson.Prediction;
import com.aicompany.backend.hitl.lesson.Lesson.Step;
import com.aicompany.backend.hitl.lesson.Lesson.StepMode;
import org.jspecify.annotations.Nullable;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 37 (ADR-044): reads a lesson file in the format of {@code LessonFormat.GUIDE}.
 *
 * <p>An agent wrote the file, so the reading is lenient where it can be -- an accent
 * more or less, {@code -} for {@code —}, a numbered list for a bulleted one -- and
 * never fails: what it cannot place it leaves out and names in {@code problems}, so
 * that the operator sees a lesson with a remark rather than an error. A heading inside
 * a code fence is code, not a heading.
 */
public final class LessonParser {

    private LessonParser() {
    }

    private static final Pattern FENCE = Pattern.compile("^\\s{0,3}(`{3,}|~{3,})\\s*([A-Za-z0-9_+#.-]*)\\s*$");
    private static final Pattern LABEL = Pattern.compile("^\\*\\*([^*:]{1,40}):?\\*\\*\\s*:?\\s*(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*+]|\\d{1,3}[.)])\\s+(.*)$");
    private static final Pattern CHECKBOX = Pattern.compile("^\\s*[-*+]\\s+\\[([ xX])]\\s+(.*)$");
    private static final Pattern FILE = Pattern.compile("^`([^`]+)`\\s*(?:\\(([^)]*)\\))?.*$");
    private static final Pattern CODE_AND_TEXT = Pattern.compile("^`([^`]+)`\\s*(?:—|–|-|:)\\s*(.+)$");
    private static final Pattern SEPARATOR = Pattern.compile("\\s+(?:—|–|-|:)\\s+|\\s*:\\s+");
    private static final Pattern NUMBER = Pattern.compile("(\\d{1,5})");

    /** A heading and the lines below it, up to the next heading of the same depth. */
    private record Section(String heading, List<String> lines) {
    }

    public static Lesson parse(String markdown) {
        List<String> lines = List.of((markdown == null ? "" : markdown).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
        List<String> problems = new ArrayList<>();
        String title = "";
        for (String line : lines) {
            if (line.startsWith("# ")) {
                title = SEPARATOR.split(line.substring(2).strip(), 2).length == 2 && key(line.substring(2)).startsWith("lezione")
                        ? SEPARATOR.split(line.substring(2).strip(), 2)[1].strip() : line.substring(2).strip();
                break;
            }
        }
        if (title.isEmpty()) {
            problems.add("Manca il titolo («# Lezione — …»).");
        }

        String intro = "";
        Prediction prediction = null;
        List<Step> steps = new ArrayList<>();
        List<Exercise> exercises = new ArrayList<>();
        List<Concept> concepts = new ArrayList<>();
        for (Section section : sections(lines, "## ")) {
            String key = key(section.heading());
            if (key.startsWith("in parole semplici")) {
                intro = text(section.lines());
            } else if (key.startsWith("prova a prevedere") || key.startsWith("prima di cominciare")) {
                prediction = prediction(section, problems);
            } else if (key.startsWith("passo")) {
                steps.add(step(section, steps.size() + 1, problems));
            } else if (key.startsWith("mettiti alla prova") || key.startsWith("esercizi")) {
                for (Section exercise : sections(section.lines(), "### ")) {
                    exercise(exercise, exercises.size() + 1, problems).ifPresent(exercises::add);
                }
            } else if (key.startsWith("cosa hai imparato") || key.startsWith("che cosa hai imparato")) {
                concepts.addAll(concepts(section.lines()));
            }
        }
        if (intro.isEmpty()) {
            problems.add("Manca «## In parole semplici».");
        }
        if (steps.isEmpty()) {
            problems.add("Non c'è nessun passo («## Passo 1 — …»).");
        }
        if (exercises.isEmpty()) {
            problems.add("Non ci sono esercizi («## Mettiti alla prova»): senza, si legge e basta.");
        }
        return new Lesson(title.isEmpty() ? "Lezione" : title, intro, prediction, List.copyOf(steps), List.copyOf(exercises),
                List.copyOf(concepts), List.copyOf(problems));
    }

    // --- the parts -----------------------------------------------------------------------

    private static @Nullable Prediction prediction(Section section, List<String> problems) {
        Map<String, String> fields = fields(section.lines());
        String question = text(before(section.lines(), "risposta"));
        String answer = fields.get("risposta");
        if (question.isEmpty() || answer == null || answer.isBlank()) {
            problems.add("«Prova a prevedere» vuole una domanda e, sotto, «**Risposta**: …».");
            return null;
        }
        return new Prediction(question, answer);
    }

    private static Step step(Section section, int number, List<String> problems) {
        String title = afterSeparator(section.heading(), "Passo " + number);
        Map<String, String> fields = fields(section.lines());
        String file = null;
        StepMode mode = StepMode.NEW;
        String declared = fields.get("file");
        if (declared != null) {
            Matcher m = FILE.matcher(declared.strip());
            if (m.matches()) {
                file = m.group(1).strip().replace('\\', '/');
                String how = m.group(2) == null ? "" : key(m.group(2));
                mode = how.contains("gia") || how.contains("scritto") ? StepMode.DONE
                        : how.contains("modific") ? StepMode.EDIT : StepMode.NEW;
            }
        }
        Fence fence = firstFence(section.lines());
        if (file == null) {
            problems.add("Passo " + number + ": manca «**File**: `percorso`», quindi non so dove va il codice.");
        }
        if (fence == null || fence.code().isBlank()) {
            problems.add("Passo " + number + ": manca il blocco di codice.");
        }
        List<Line> explained = new ArrayList<>();
        for (String item : list(section.lines(), "riga per riga")) {
            Matcher m = CODE_AND_TEXT.matcher(item);
            explained.add(m.matches() ? new Line(m.group(1), m.group(2).strip()) : new Line(null, item));
        }
        if (explained.isEmpty() && fence != null) {
            problems.add("Passo " + number + ": manca la spiegazione «**Riga per riga**».");
        }
        String why = fields.get("perche");
        return new Step(title, file, mode, fence == null ? null : fence.language(), fence == null ? "" : fence.code(),
                List.copyOf(explained), why == null || why.isBlank() ? null : why);
    }

    private static java.util.Optional<Exercise> exercise(Section section, int number, List<String> problems) {
        String heading = key(section.heading());
        Kind kind = heading.startsWith("quiz") ? Kind.QUIZ
                : heading.startsWith("completa") ? Kind.FILL
                : heading.startsWith("trova l'errore") || heading.startsWith("trova l errore") || heading.startsWith("trova il bug") ? Kind.BUG
                : heading.startsWith("spiega") ? Kind.EXPLAIN : null;
        if (kind == null) {
            problems.add("Esercizio " + number + ": «" + section.heading() + "» non è Quiz, Completa, Trova l'errore o Spiega a parole tue.");
            return java.util.Optional.empty();
        }
        String prompt = afterSeparator(section.heading(), section.heading());
        Map<String, String> fields = fields(section.lines());
        Fence fence = firstFence(section.lines());
        String explanation = blankToNull(fields.get("spiegazione"));
        String solution = blankToNull(fields.get("soluzione"));
        List<Option> options = new ArrayList<>();
        List<String> keyPoints = List.of();
        switch (kind) {
            case QUIZ -> {
                for (String line : section.lines()) {
                    Matcher box = CHECKBOX.matcher(line);
                    if (box.matches()) {
                        options.add(new Option(box.group(2).strip(), !box.group(1).isBlank()));
                    }
                }
                if (options.size() < 2 || options.stream().filter(Option::correct).count() != 1) {
                    problems.add("Esercizio " + number + ": un quiz vuole almeno due opzioni e una sola segnata con [x].");
                    return java.util.Optional.empty();
                }
            }
            case FILL -> {
                if (fence == null || !fence.code().contains("___") || solution == null) {
                    problems.add("Esercizio " + number + ": «Completa» vuole un codice con ___ e «**Soluzione**: `…`».");
                    return java.util.Optional.empty();
                }
                solution = solution.replaceAll("^`+|`+$", "").strip();
            }
            case BUG -> {
                Matcher line = NUMBER.matcher(solution == null ? "" : solution);
                if (fence == null || !line.find()) {
                    problems.add("Esercizio " + number + ": «Trova l'errore» vuole un codice e «**Soluzione**: riga N».");
                    return java.util.Optional.empty();
                }
                solution = line.group(1);
            }
            case EXPLAIN -> {
                keyPoints = list(section.lines(), "punti chiave");
                if (keyPoints.isEmpty()) {
                    problems.add("Esercizio " + number + ": «Spiega a parole tue» vuole i «**Punti chiave**» di una buona risposta.");
                    return java.util.Optional.empty();
                }
            }
        }
        return java.util.Optional.of(new Exercise(kind, prompt, List.copyOf(options), fence == null ? null : fence.language(),
                fence == null ? null : fence.code(), solution, explanation, List.copyOf(keyPoints)));
    }

    private static List<Concept> concepts(List<String> lines) {
        List<Concept> out = new ArrayList<>();
        for (String line : outsideFences(lines)) {
            Matcher bullet = BULLET.matcher(line);
            if (!bullet.matches()) {
                continue;
            }
            String item = bullet.group(1).strip();
            Matcher label = LABEL.matcher(item);
            if (label.matches() && !label.group(2).isBlank()) {
                out.add(new Concept(label.group(1).strip(), label.group(2).strip()));
            } else {
                String[] parts = SEPARATOR.split(item.replace("**", ""), 2);
                if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                    out.add(new Concept(parts[0].strip(), parts[1].strip()));
                }
            }
        }
        return out;
    }

    // --- reading Markdown ----------------------------------------------------------------

    private record Fence(@Nullable String language, String code) {
    }

    /** The lines that are not inside a code fence, fence lines excluded. */
    private static List<String> outsideFences(List<String> lines) {
        List<String> out = new ArrayList<>();
        String open = null;
        for (String line : lines) {
            Matcher fence = FENCE.matcher(line);
            if (open == null && fence.matches()) {
                open = fence.group(1);
            } else if (open != null) {
                if (fence.matches() && fence.group(1).charAt(0) == open.charAt(0) && fence.group(1).length() >= open.length()
                        && fence.group(2).isEmpty()) {
                    open = null;
                }
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static List<Section> sections(List<String> lines, String marker) {
        List<Section> out = new ArrayList<>();
        Section current = null;
        String open = null;
        for (String line : lines) {
            Matcher fence = FENCE.matcher(line);
            if (open == null && fence.matches()) {
                open = fence.group(1);
            } else if (open != null && fence.matches() && fence.group(1).charAt(0) == open.charAt(0)
                    && fence.group(1).length() >= open.length() && fence.group(2).isEmpty()) {
                open = null;
            } else if (open == null && line.startsWith(marker)) {
                current = new Section(line.substring(marker.length()).strip(), new ArrayList<>());
                out.add(current);
                continue;
            }
            if (current != null) {
                current.lines().add(line);
            }
        }
        return out;
    }

    private static @Nullable Fence firstFence(List<String> lines) {
        String open = null;
        String language = null;
        StringBuilder code = new StringBuilder();
        for (String line : lines) {
            Matcher fence = FENCE.matcher(line);
            if (open == null) {
                if (fence.matches()) {
                    open = fence.group(1);
                    language = fence.group(2).isEmpty() ? null : fence.group(2).toLowerCase(Locale.ROOT);
                }
            } else if (fence.matches() && fence.group(1).charAt(0) == open.charAt(0) && fence.group(1).length() >= open.length()
                    && fence.group(2).isEmpty()) {
                return new Fence(language, code.toString().stripTrailing());
            } else {
                code.append(line).append('\n');
            }
        }
        return open == null ? null : new Fence(language, code.toString().stripTrailing());
    }

    /**
     * The {@code **Label**: value} fields of a section, by the label's key. A value runs on
     * the lines below it until a blank line, another label, a list or a fence.
     */
    private static Map<String, String> fields(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        String label = null;
        StringBuilder value = new StringBuilder();
        for (String line : outsideFences(lines)) {
            Matcher m = LABEL.matcher(line.strip());
            if (m.matches()) {
                if (label != null) {
                    out.putIfAbsent(label, value.toString().strip());
                }
                label = key(m.group(1));
                value = new StringBuilder(m.group(2));
            } else if (label != null) {
                if (line.isBlank() || BULLET.matcher(line).matches() || line.startsWith("#")) {
                    out.putIfAbsent(label, value.toString().strip());
                    label = null;
                } else {
                    value.append(' ').append(line.strip());
                }
            }
        }
        if (label != null) {
            out.putIfAbsent(label, value.toString().strip());
        }
        return out;
    }

    /** The items of the list that follows {@code **Label**}. */
    private static List<String> list(List<String> lines, String labelKey) {
        List<String> out = new ArrayList<>();
        boolean in = false;
        for (String line : outsideFences(lines)) {
            Matcher label = LABEL.matcher(line.strip());
            if (label.matches()) {
                if (in) {
                    break;
                }
                in = key(label.group(1)).startsWith(labelKey);
                continue;
            }
            if (!in) {
                continue;
            }
            Matcher bullet = BULLET.matcher(line);
            if (bullet.matches()) {
                out.add(bullet.group(1).strip());
            } else if (!line.isBlank() && !out.isEmpty() && !line.startsWith("#")) {
                out.set(out.size() - 1, out.getLast() + " " + line.strip());
            }
        }
        return out;
    }

    /** The prose before the first label of that key. */
    private static List<String> before(List<String> lines, String labelKey) {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Matcher label = LABEL.matcher(line.strip());
            if (label.matches() && key(label.group(1)).startsWith(labelKey)) {
                break;
            }
            out.add(line);
        }
        return out;
    }

    private static String text(List<String> lines) {
        return String.join("\n", lines).strip();
    }

    /** {@code Passo 2 — Il modello} gives {@code Il modello}; a heading without a separator gives the fallback. */
    private static String afterSeparator(String heading, String fallback) {
        String[] parts = SEPARATOR.split(heading.strip(), 2);
        return parts.length == 2 && !parts[1].isBlank() ? parts[1].strip() : fallback;
    }

    /** Lower-case, without accents or punctuation at the ends: {@code Perché} and {@code perche} are one label. */
    private static String key(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace('’', '\'').strip();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
