package com.aicompany.backend.software.detect;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a catalog path template to a file that exists on this machine.
 *
 * <p>Two features and nothing else: {@code %NAME%} environment placeholders, so
 * a catalog entry is not tied to one user's home, and a {@code *} inside a path
 * segment, so that {@code JetBrains\IntelliJ IDEA *\bin\idea64.exe} still finds
 * IntelliJ after an update renames its folder. When a wildcard matches several
 * folders the last one in name order wins -- for versioned folders, the newest.
 *
 * <p>A template naming a variable that is not set resolves to nothing rather than
 * to a path with a literal {@code %NAME%} in it.
 */
public final class PathTemplate {

    private static final Pattern VARIABLE = Pattern.compile("%([A-Za-z0-9_()]+)%");

    private PathTemplate() {
    }

    public static Optional<String> expand(String template, Map<String, String> environment) {
        if (template == null || template.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = lookup(environment, matcher.group(1));
            if (value == null) {
                return Optional.empty();
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);
        return Optional.of(out.toString());
    }

    /** Windows environment names are case-insensitive; a map from the JVM is not. */
    private static String lookup(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null) {
            return exact;
        }
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    public static Optional<Path> resolveFile(String template, Map<String, String> environment) {
        return expand(template, environment).flatMap(PathTemplate::resolveWildcards)
                .filter(Files::isRegularFile);
    }

    private static Optional<Path> resolveWildcards(String expanded) {
        String normalized = expanded.replace('/', '\\');
        if (!normalized.contains("*")) {
            return Optional.of(Path.of(expanded));
        }
        String[] segments = normalized.split("\\\\");
        List<Path> candidates = new ArrayList<>();
        candidates.add(Path.of(segments[0] + "\\"));
        for (int i = 1; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }
            List<Path> next = new ArrayList<>();
            for (Path base : candidates) {
                if (!segment.contains("*")) {
                    next.add(base.resolve(segment));
                    continue;
                }
                Pattern glob = Pattern.compile(Pattern.quote(segment).replace("*", "\\E.*\\Q"),
                        Pattern.CASE_INSENSITIVE);
                if (!Files.isDirectory(base)) {
                    continue;
                }
                try (DirectoryStream<Path> children = Files.newDirectoryStream(base)) {
                    for (Path child : children) {
                        if (glob.matcher(child.getFileName().toString()).matches()) {
                            next.add(child);
                        }
                    }
                } catch (IOException unreadable) {
                    // A folder we cannot list contributes no candidate.
                }
            }
            candidates = next;
        }
        return candidates.stream()
                .filter(Files::exists)
                .max(Comparator.comparing(path -> path.toString().toLowerCase()));
    }
}
