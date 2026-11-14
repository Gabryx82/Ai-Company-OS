package com.aicompany.backend.journal;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 37 (ADR-047): the diary an agent keeps while it works on a handed-off task, in a
 * file beside the task document. One list item per step, newest last, each with the time
 * the agent took from the system clock. Pure functions: reading the file is the caller's.
 */
public final class DiaryFormat {

    public static final int MAX_STEPS = 300;
    public static final int MAX_TEXT = 400;

    /** A list item: {@code - }, {@code * } or {@code + } and some text. */
    private static final Pattern ITEM = Pattern.compile("^\\s*[-*+]\\s+(.*\\S)\\s*$");
    /**
     * A time at the start of the item, in the shapes agents write: ISO-8601 with or without the
     * offset, a space for the T, or the time of day alone; in brackets or not; then a separator.
     */
    private static final Pattern STAMP = Pattern.compile("^\\[?("
            + "\\d{4}-\\d{2}-\\d{2}[T ]\\d{1,2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?"
            + "|\\d{1,2}:\\d{2}(?::\\d{2})?"
            + ")\\]?(?:\\s*[·—–|:-]\\s*|\\s+)(.+)$");

    private DiaryFormat() {
    }

    /** One step of the diary; {@code approximate} when the agent wrote no time and one was inferred. */
    public record Step(int position, @Nullable Instant at, boolean approximate, String text) {
    }

    /** The diary as the handoff creates it: a header that says how to write in it, no step yet. */
    public static String template(String label) {
        return """
                # Diario — %s

                > Scritto dall'agente mentre lavora: una riga per passo, aggiunta in fondo, con l'ora del sistema.
                > AI Company OS lo legge e lo mostra nell'Ufficio 3D. Non cancellare né riscrivere le righe già scritte.
                > Esempio: `- 2026-11-13T12:04:05+01:00 · Letto la task e la fase`

                """.formatted(label);
    }

    /**
     * The steps of a diary, oldest first, the last {@link #MAX_STEPS}. A step without a time takes
     * the one before it (or {@code notBefore}, the delivery, for the first ones); the last one takes
     * the file's own time, when later -- it is the line that changed the file last.
     *
     * @param modified  when the file was last written, or null
     * @param notBefore when the work could begin at the earliest, or null
     * @param zone      the zone of a time written without an offset
     */
    public static List<Step> parse(String text, @Nullable Instant modified, @Nullable Instant notBefore, ZoneId zone) {
        LocalDate day = (modified != null ? modified : notBefore != null ? notBefore : Instant.now()).atZone(zone).toLocalDate();
        List<Step> steps = new ArrayList<>();
        Instant previous = notBefore;
        int position = 0;
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher item = ITEM.matcher(lines[i]);
            if (!item.matches()) {
                continue;
            }
            String body = item.group(1);
            Instant at = null;
            Matcher stamp = STAMP.matcher(body);
            if (stamp.matches()) {
                at = time(stamp.group(1), day, zone);
                if (at != null) {
                    body = stamp.group(2).strip();
                }
            }
            boolean approximate = at == null;
            if (approximate) {
                at = previous;
            }
            steps.add(new Step(position++, at, approximate, cut(body)));
            previous = at;
        }
        if (!steps.isEmpty()) {
            Step last = steps.getLast();
            if (last.approximate() && modified != null && (last.at() == null || modified.isAfter(last.at()))) {
                steps.set(steps.size() - 1, new Step(last.position(), modified, true, last.text()));
            }
        }
        return steps.size() > MAX_STEPS ? List.copyOf(steps.subList(steps.size() - MAX_STEPS, steps.size())) : steps;
    }

    private static @Nullable Instant time(String written, LocalDate day, ZoneId zone) {
        try {
            if (written.length() <= 8) {
                return LocalTime.parse(written.length() == 4 || written.length() == 7 ? "0" + written : written)
                        .atDate(day).atZone(zone).toInstant();
            }
            String iso = written.replace(' ', 'T').replace(',', '.');
            int t = iso.indexOf('T');
            if (iso.charAt(t + 2) == ':') {
                iso = iso.substring(0, t + 1) + "0" + iso.substring(t + 1);
            }
            if (iso.endsWith("Z") || iso.matches(".*[+-]\\d{2}:?\\d{2}$")) {
                iso = iso.replaceFirst("([+-]\\d{2})(\\d{2})$", "$1:$2");
                return OffsetDateTime.parse(iso).toInstant();
            }
            return LocalDateTime.parse(iso).atZone(zone).toInstant();
        } catch (DateTimeParseException | StringIndexOutOfBoundsException unreadable) {
            return null;
        }
    }

    private static String cut(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }
}
