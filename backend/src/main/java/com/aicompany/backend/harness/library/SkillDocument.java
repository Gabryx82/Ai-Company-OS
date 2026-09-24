package com.aicompany.backend.harness.library;

import com.aicompany.backend.harness.exception.LibraryProblemException;
import com.aicompany.backend.harness.model.HarnessResource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A skill or knowledge file (ADR-026 §1): a small YAML-like frontmatter, then
 * the instructions in Markdown. The same shape Claude Code uses for its
 * {@code SKILL.md}, so a skill written here reads naturally there too.
 *
 * <pre>
 * ---
 * key: clean-code-java
 * name: Clean code Java / Spring
 * kind: SKILL
 * description: Naming, single responsibility, explicit transactions.
 * tags: [java, spring]
 * source: https://example.org/skill.md
 * ---
 *
 * # Instructions ...
 * </pre>
 *
 * Only these keys are read, one per line; anything else in the frontmatter is
 * kept in the file and ignored by the index.
 */
public record SkillDocument(String key, String name, HarnessResource.Kind kind, String description,
                            List<String> tags, String source, String body) {

    public static final Pattern KEY = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final String FENCE = "---";

    public static SkillDocument parse(String text, String expectedKey) {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith(FENCE + "\n")) {
            throw LibraryProblemException.invalidDocument("The file must start with a frontmatter between two '---' lines");
        }
        int end = normalized.indexOf("\n" + FENCE, FENCE.length());
        if (end < 0) {
            throw LibraryProblemException.invalidDocument("The frontmatter is not closed by a '---' line");
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : normalized.substring(FENCE.length() + 1, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith(" ") && !line.startsWith("#")) {
                values.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), unquote(line.substring(colon + 1).strip()));
            }
        }
        String body = normalized.substring(Math.min(normalized.length(), end + FENCE.length() + 1)).strip();

        String key = values.getOrDefault("key", expectedKey);
        if (key == null || !KEY.matcher(key).matches()) {
            throw LibraryProblemException.invalidDocument("'key' must be lower-case letters, digits and dashes (2-64)");
        }
        if (expectedKey != null && !expectedKey.equals(key)) {
            throw LibraryProblemException.invalidDocument("The key of the file ('" + key + "') must stay '" + expectedKey + "'");
        }
        String name = values.get("name");
        if (name == null || name.isBlank() || name.length() > 120) {
            throw LibraryProblemException.invalidDocument("'name' is required, at most 120 characters");
        }
        HarnessResource.Kind kind;
        try {
            kind = HarnessResource.Kind.valueOf(values.getOrDefault("kind", "SKILL").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw LibraryProblemException.invalidDocument("'kind' must be SKILL or KNOWLEDGE");
        }
        if (kind != HarnessResource.Kind.SKILL && kind != HarnessResource.Kind.KNOWLEDGE) {
            throw LibraryProblemException.invalidDocument("'kind' must be SKILL or KNOWLEDGE");
        }
        String description = values.get("description");
        if (description != null && description.length() > 2000) {
            throw LibraryProblemException.invalidDocument("'description' is at most 2000 characters");
        }
        return new SkillDocument(key, name.strip(), kind, blankToNull(description), tags(values.get("tags")),
                blankToNull(values.get("source")), body);
    }

    public String render() {
        StringBuilder text = new StringBuilder(FENCE).append('\n');
        text.append("key: ").append(key).append('\n');
        text.append("name: ").append(name).append('\n');
        text.append("kind: ").append(kind.name()).append('\n');
        if (description != null) {
            text.append("description: ").append(description.replace('\n', ' ')).append('\n');
        }
        text.append("tags: [").append(String.join(", ", tags)).append("]\n");
        if (source != null) {
            text.append("source: ").append(source).append('\n');
        }
        text.append(FENCE).append("\n\n").append(body == null ? "" : body.strip()).append('\n');
        return text.toString();
    }

    private static List<String> tags(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        String inner = value.strip();
        if (inner.startsWith("[") && inner.endsWith("]")) {
            inner = inner.substring(1, inner.length() - 1);
        }
        List<String> out = new ArrayList<>();
        Arrays.stream(inner.split(",")).map(t -> unquote(t.strip())).filter(t -> !t.isEmpty())
                .map(t -> t.toLowerCase(Locale.ROOT)).forEach(out::add);
        return List.copyOf(out);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
