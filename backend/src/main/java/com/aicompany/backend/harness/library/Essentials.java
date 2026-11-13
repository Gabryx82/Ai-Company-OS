package com.aicompany.backend.harness.library;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 35 (ADR-041 §7): the part of a skill or knowledge entry that goes into an engine
 * run -- its {@code ## Essenziale} section when it has one, otherwise its body up to the
 * first second-level heading, without the title. Never the first characters taken
 * blindly: over the limit it is cut at the last paragraph that fits (then the last line,
 * then the last word), and the cut is marked.
 */
public final class Essentials {

    /** The mark of a cut: the rest is in the file. */
    public static final String CUT = "\n[…]";

    private static final Pattern ESSENZIALE = Pattern.compile("(?mi)^##[ \\t]+Essenziale[ \\t]*$");
    private static final Pattern SECOND_LEVEL = Pattern.compile("(?m)^##[ \\t]");
    private static final Pattern TITLE = Pattern.compile("\\A#[ \\t][^\\n]*\\n?");

    private Essentials() {
    }

    /**
     * @param body        the entry's Markdown, without its frontmatter
     * @param description the entry's description: what stands in for an empty introduction
     * @param limit       the most characters it may have, the cut mark aside
     */
    public static Optional<String> of(String body, String description, int limit) {
        String text = body == null ? "" : body.replace("\r\n", "\n");
        Matcher essential = ESSENZIALE.matcher(text);
        String part;
        if (essential.find()) {
            String after = text.substring(essential.end());
            Matcher next = SECOND_LEVEL.matcher(after);
            part = next.find() ? after.substring(0, next.start()) : after;
        } else {
            Matcher first = SECOND_LEVEL.matcher(text);
            part = first.find() ? text.substring(0, first.start()) : text;
            part = TITLE.matcher(part.strip()).replaceFirst("");
        }
        part = part.strip();
        if (part.isEmpty()) {
            part = description == null ? "" : description.strip();
        }
        if (part.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(part.length() <= limit ? part : cut(part, limit));
    }

    private static String cut(String text, int limit) {
        for (String boundary : new String[] {"\n\n", "\n", " "}) {
            int at = text.lastIndexOf(boundary, limit);
            if (at > 0) {
                return text.substring(0, at).stripTrailing() + CUT;
            }
        }
        // One word longer than the limit: nothing smaller to cut at.
        return text.substring(0, limit) + CUT;
    }
}
