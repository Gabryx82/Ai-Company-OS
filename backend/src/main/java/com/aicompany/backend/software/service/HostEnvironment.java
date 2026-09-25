package com.aicompany.backend.software.service;

import com.aicompany.backend.software.detect.PathTemplate;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The machine the control plane runs on -- environment variables, operating
 * system, and where Windows Terminal is -- as one injectable value, so that
 * tests describe a machine instead of reading the real one.
 *
 * @param windowsTerminal {@code wt.exe}, or {@code null} when it is not installed.
 */
public record HostEnvironment(Map<String, String> variables, boolean windows, Path windowsTerminal, Path home) {

    public static HostEnvironment current() {
        Map<String, String> variables = System.getenv();
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        return new HostEnvironment(variables, windows, findWindowsTerminal(variables).orElse(null),
                Path.of(System.getProperty("user.home")));
    }

    /**
     * The Store install puts {@code wt.exe} in {@code WindowsApps} as an app-execution
     * alias: a reparse point Java cannot follow, so {@code Files.exists(path)} answers
     * false for a terminal that is there -- found by the operator on 2026-09-25, every
     * command-line tool refused with "wt.exe is not installed". The alias is checked
     * without following it; an install elsewhere is found on the PATH.
     */
    static Optional<Path> findWindowsTerminal(Map<String, String> variables) {
        Stream<Path> alias = PathTemplate.expand("%LOCALAPPDATA%\\Microsoft\\WindowsApps\\wt.exe", variables)
                .map(Path::of).stream();
        String path = variables.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("PATH"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        Stream<Path> onPath = Stream.of(path.split(File.pathSeparator)).filter(dir -> !dir.isBlank())
                .flatMap(dir -> {
                    try {
                        return Stream.of(Path.of(dir.strip(), "wt.exe"));
                    } catch (InvalidPathException malformed) {
                        return Stream.empty();
                    }
                });
        return Stream.concat(alias, onPath)
                .filter(candidate -> Files.exists(candidate, LinkOption.NOFOLLOW_LINKS))
                .findFirst();
    }

    public Optional<Path> terminal() {
        return Optional.ofNullable(windowsTerminal);
    }
}
