package com.aicompany.backend.harness.hub;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The frontmatter of a {@code SKILL.md} as the applications write it (ADR-036 §3):
 * the subset of YAML real skills use -- plain, quoted and block scalars
 * ({@code description: >-} followed by indented lines), one level of nesting
 * ({@code metadata:} / {@code   version: "1.0"}, read as {@code metadata.version})
 * and lists, joined as {@code [a, b]}. Anything else is skipped, never guessed.
 *
 * <p>Tolerant on purpose: a skill written for Claude Code, Codex or Antigravity is
 * read as it is, and a value this reader does not understand is simply absent.
 */
public final class Frontmatter {

    private static final Pattern KEY_LINE = Pattern.compile("^([A-Za-z0-9_.-]+):(.*)$");
    private static final Pattern NESTED_LINE = Pattern.compile("^(\\s+)([A-Za-z0-9_.-]+):(.*)$");

    private Frontmatter() {
    }

    /** The block between the first two {@code ---} lines, or empty when the text has none. */
    public static Optional<String> block(String text) {
        String normalized = normalize(text);
        if (!normalized.startsWith("---\n")) {
            return Optional.empty();
        }
        int end = normalized.indexOf("\n---", 3);
        if (end < 0) {
            return Optional.empty();
        }
        return Optional.of(normalized.substring(4, end + 1));
    }

    /** What follows the frontmatter, or the whole text when it has none. */
    public static String body(String text) {
        String normalized = normalize(text);
        if (!normalized.startsWith("---\n")) {
            return normalized;
        }
        int end = normalized.indexOf("\n---", 3);
        if (end < 0) {
            return normalized;
        }
        int after = normalized.indexOf('\n', end + 4);
        return after < 0 ? "" : normalized.substring(after + 1);
    }

    /** The values of a whole document's frontmatter; empty when it has none. Keys are lower-cased. */
    public static Map<String, String> read(String text) {
        return block(text).map(Frontmatter::parse).orElseGet(LinkedHashMap::new);
    }

    /** The values of a frontmatter block (without its fences). Keys are lower-cased. */
    public static Map<String, String> parse(String block) {
        Map<String, String> values = new LinkedHashMap<>();
        List<String> lines = List.of(normalize(block).split("\n", -1));
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            Matcher key = KEY_LINE.matcher(line);
            if (line.isBlank() || line.startsWith("#") || !key.matches()) {
                i++;
                continue;
            }
            String name = key.group(1).toLowerCase(Locale.ROOT);
            String raw = key.group(2) == null ? "" : key.group(2).strip();
            int next = i + 1;
            List<String> indented = new ArrayList<>();
            while (next < lines.size() && (lines.get(next).isBlank() || Character.isWhitespace(lines.get(next).charAt(0)))) {
                indented.add(lines.get(next));
                next++;
            }
            // Trailing blank lines belong to nobody.
            while (!indented.isEmpty() && indented.getLast().isBlank()) {
                indented.removeLast();
            }
            if (raw.startsWith("\"") || raw.startsWith("'")) {
                values.put(name, quoted(raw, indented));
            } else if (raw.matches("[|>][+-]?")) {
                values.put(name, blockScalar(raw.charAt(0) == '>', indented));
            } else if (raw.isEmpty() && !indented.isEmpty()) {
                nested(name, indented, values);
            } else {
                // A " #" is kept: the library's own files never treated it as a comment ("Ticket #42").
                StringBuilder plain = new StringBuilder(raw);
                for (String more : indented) {
                    if (!more.isBlank()) {
                        plain.append(' ').append(more.strip());
                    }
                }
                values.put(name, plain.toString().strip());
            }
            i = next;
        }
        return values;
    }

    private static void nested(String parent, List<String> lines, Map<String, String> values) {
        List<String> items = new ArrayList<>();
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#")) {
                i++;
                continue;
            }
            if (stripped.startsWith("- ") || stripped.equals("-")) {
                items.add(unquote(stripped.substring(1).strip()));
                i++;
                continue;
            }
            Matcher child = NESTED_LINE.matcher(line);
            if (!child.matches()) {
                i++;
                continue;
            }
            int indent = child.group(1).length();
            String raw = child.group(3) == null ? "" : child.group(3).strip();
            List<String> deeper = new ArrayList<>();
            int next = i + 1;
            while (next < lines.size() && (lines.get(next).isBlank() || leading(lines.get(next)) > indent)) {
                deeper.add(lines.get(next));
                next++;
            }
            String key = parent + "." + child.group(2).toLowerCase(Locale.ROOT);
            if (raw.startsWith("\"") || raw.startsWith("'")) {
                values.put(key, quoted(raw, deeper));
            } else if (raw.matches("[|>][+-]?")) {
                values.put(key, blockScalar(raw.charAt(0) == '>', deeper));
            } else if (!raw.isEmpty()) {
                values.put(key, raw);
            }
            i = next;
        }
        if (!items.isEmpty()) {
            values.put(parent, "[" + String.join(", ", items) + "]");
        }
    }

    private static String blockScalar(boolean folded, List<String> lines) {
        int indent = lines.stream().filter(l -> !l.isBlank()).mapToInt(Frontmatter::leading).min().orElse(0);
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String content = line.isBlank() ? "" : line.substring(Math.min(indent, line.length()));
            if (folded) {
                if (content.isEmpty()) {
                    out.append('\n');
                } else {
                    if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                        out.append(' ');
                    }
                    out.append(content.strip());
                }
            } else {
                out.append(content).append('\n');
            }
        }
        return out.toString().strip();
    }

    private static String quoted(String raw, List<String> continuation) {
        char quote = raw.charAt(0);
        StringBuilder all = new StringBuilder(raw);
        for (String more : continuation) {
            all.append(' ').append(more.strip());
        }
        String text = all.toString();
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote == '\'' && c == '\'') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                return out.toString();
            }
            if (quote == '"' && c == '\\' && i + 1 < text.length()) {
                char escaped = text.charAt(++i);
                out.append(switch (escaped) {
                    case 'n' -> '\n';
                    case 't' -> '\t';
                    default -> escaped;
                });
                continue;
            }
            if (quote == '"' && c == '"') {
                return out.toString();
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && (value.startsWith("\"") || value.startsWith("'"))) {
            return quoted(value, List.of());
        }
        return value;
    }

    private static int leading(String line) {
        int n = 0;
        while (n < line.length() && Character.isWhitespace(line.charAt(n))) {
            n++;
        }
        return n;
    }

    private static String normalize(String text) {
        String out = text == null ? "" : text.replace("\r\n", "\n");
        return out.startsWith("﻿") ? out.substring(1) : out;
    }
}
