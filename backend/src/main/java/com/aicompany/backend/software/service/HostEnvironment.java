package com.aicompany.backend.software.service;

import com.aicompany.backend.software.detect.PathTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * The machine the control plane runs on -- environment variables, operating
 * system, and where Windows Terminal is -- as one injectable value, so that
 * tests describe a machine instead of reading the real one.
 *
 * @param windowsTerminal {@code wt.exe}, or {@code null} when it is not installed.
 *                        An app-execution alias is a reparse point, not a regular
 *                        file, so existence is what is checked.
 */
public record HostEnvironment(Map<String, String> variables, boolean windows, Path windowsTerminal, Path home) {

    public static HostEnvironment current() {
        Map<String, String> variables = System.getenv();
        boolean windows = System.getProperty("os.name", "").toLowerCase().startsWith("windows");
        Path terminal = PathTemplate.expand("%LOCALAPPDATA%\\Microsoft\\WindowsApps\\wt.exe", variables)
                .map(Path::of)
                .filter(Files::exists)
                .orElse(null);
        return new HostEnvironment(variables, windows, terminal, Path.of(System.getProperty("user.home")));
    }

    public Optional<Path> terminal() {
        return Optional.ofNullable(windowsTerminal);
    }
}
