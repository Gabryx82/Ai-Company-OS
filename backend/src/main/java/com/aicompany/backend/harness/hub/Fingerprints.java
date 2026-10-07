package com.aicompany.backend.harness.hub;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Fingerprints of skill folders (ADR-036 §5). Two kinds, for two questions:
 * <ul>
 *   <li>{@link #content} -- did anything in this folder change? Every file's bytes,
 *       by relative path. Used where the Hub decides whether it may overwrite;</li>
 *   <li>{@link #quick} -- did this skill change since the last synchronisation?
 *       The {@code SKILL.md} bytes and the name and size of the other files: cheap
 *       enough to run over every application every few minutes.</li>
 * </ul>
 */
public final class Fingerprints {

    /** The Hub's own file inside a copy it wrote; never part of a fingerprint. */
    public static final String MARKER = ".aicos-hub.json";

    static final int MAX_FILES = 500;
    static final int MAX_DEPTH = 6;

    private Fingerprints() {
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** The fingerprint of a package of files, by relative path: the same files give the same value wherever they are. */
    public static String of(Map<String, byte[]> files) {
        StringBuilder all = new StringBuilder();
        new TreeMap<>(files).forEach((path, bytes) -> {
            if (!path.endsWith(MARKER)) {
                all.append(path).append('\u0000').append(sha256(bytes)).append('\n');
            }
        });
        return sha256(all.toString());
    }

    /** Every file of a skill folder, read: its content fingerprint. */
    public static String content(Path folder) {
        return of(read(folder));
    }

    /** The files of a skill folder by relative path ('/'), without the Hub's marker, hidden files or links. */
    public static Map<String, byte[]> read(Path folder) {
        Map<String, byte[]> out = new TreeMap<>();
        for (Path file : files(folder)) {
            try {
                out.put(relative(folder, file), Files.readAllBytes(file));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    /** {@code SKILL.md} and the shape of its siblings. */
    public static String quick(Path folder, String skillMdHash) {
        StringBuilder all = new StringBuilder(skillMdHash).append('\n');
        for (Path file : files(folder)) {
            String rel = relative(folder, file);
            if (!rel.equals("SKILL.md")) {
                try {
                    all.append(rel).append(':').append(Files.size(file)).append('\n');
                } catch (IOException e) {
                    all.append(rel).append(":?\n");
                }
            }
        }
        return sha256(all.toString());
    }

    /** The regular files below a folder, sorted, at most {@link #MAX_FILES}; links and hidden entries left out. */
    public static List<Path> files(Path folder) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) {
            return out;
        }
        try (Stream<Path> walk = Files.walk(folder, MAX_DEPTH)) {
            walk.filter(p -> !p.equals(folder))
                    .filter(p -> folder.relativize(p).toString().replace('\\', '/').chars().noneMatch(c -> c == 0))
                    .filter(p -> noHiddenPart(folder, p))
                    .filter(p -> Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS))
                    .sorted()
                    .limit(MAX_FILES)
                    .forEach(out::add);
        } catch (IOException | UncheckedIOException e) {
            return out;
        }
        return out;
    }

    static String relative(Path folder, Path file) {
        return folder.relativize(file).toString().replace('\\', '/');
    }

    private static boolean noHiddenPart(Path folder, Path file) {
        for (Path part : folder.relativize(file)) {
            String name = part.toString();
            if (name.startsWith(".") || name.equals("node_modules") || name.equals("__pycache__")) {
                return false;
            }
        }
        return true;
    }
}
