package com.aicompany.backend.harness.hub;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The paths of a skill package, which come from a marketplace and so are
 * untrusted (ADR-036 §7). A path is relative, forward-slashed, made of plain
 * names -- no {@code ..}, no drive, no device name -- and once resolved it must
 * stay inside the folder it is written to. A package has at most
 * {@value #MAX_FILES} files, each at most {@value #MAX_FILE} bytes, in all
 * {@value #MAX_TOTAL} bytes.
 */
public final class PackagePaths {

    public static final int MAX_FILES = 150;
    public static final int MAX_FILE = 1024 * 1024;
    public static final long MAX_TOTAL = 8L * 1024 * 1024;

    private static final Pattern SEGMENT = Pattern.compile("^[\\p{L}\\p{N} _.,()+@&'=\\[\\]~-]{1,120}$");
    private static final Set<String> DEVICES = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4",
            "com5", "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private PackagePaths() {
    }

    public static boolean valid(String path) {
        if (path == null || path.isEmpty() || path.length() > 400 || path.startsWith("/") || path.endsWith("/")) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")
                    || segment.endsWith(".") || segment.endsWith(" ")
                    || DEVICES.contains(segment.toLowerCase(Locale.ROOT).replaceFirst("\\..*$", ""))) {
                return false;
            }
        }
        return true;
    }

    /** Files or folders whose name starts with a dot are not part of a skill as the Hub installs it. */
    public static boolean hidden(String path) {
        for (String segment : path.split("/")) {
            // "." and ".." are not hidden names: they are paths that leave, and require() refuses them.
            if (segment.startsWith(".") && !segment.equals(".") && !segment.equals("..")) {
                return true;
            }
        }
        return false;
    }

    public static void require(String path) {
        if (!valid(path)) {
            throw HubProblemException.packageRefused("The package has a file with a path the Hub does not write: " + path);
        }
    }

    public static void requireBudget(int files, long totalBytes) {
        if (files > MAX_FILES) {
            throw HubProblemException.packageRefused("The package has more than " + MAX_FILES + " files");
        }
        if (totalBytes > MAX_TOTAL) {
            throw HubProblemException.packageRefused("The package is larger than " + (MAX_TOTAL / 1024 / 1024) + " MB");
        }
    }

    /** {@code folder/path}, checked to stay inside {@code folder}. */
    public static Path resolve(Path folder, String path) {
        require(path);
        Path resolved = folder.resolve(path).normalize();
        if (!resolved.startsWith(folder.normalize())) {
            throw HubProblemException.packageRefused("A path left its folder: " + path);
        }
        return resolved;
    }
}
