package com.aicompany.backend.software.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HostEnvironmentTest {

    @Test
    void findsATerminalInstalledOnThePath(@TempDir Path root) throws IOException {
        Path elsewhere = Files.createDirectories(root.resolve("Terminal"));
        Files.createFile(elsewhere.resolve("wt.exe"));
        String path = root.resolve("nothing-here") + File.pathSeparator + elsewhere;

        assertThat(HostEnvironment.findWindowsTerminal(Map.of("LOCALAPPDATA", root.resolve("Local").toString(), "Path", path)))
                .contains(elsewhere.resolve("wt.exe"));
    }

    /** Windows only: the alias location is a Windows path template, backslashes and all. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void prefersTheStoreAliasOverThePath(@TempDir Path root) throws IOException {
        Path apps = Files.createDirectories(root.resolve("Local/Microsoft/WindowsApps"));
        Path elsewhere = Files.createDirectories(root.resolve("Terminal"));
        Files.createFile(elsewhere.resolve("wt.exe"));
        String path = elsewhere.toString();
        Files.createFile(apps.resolve("wt.exe"));
        assertThat(HostEnvironment.findWindowsTerminal(Map.of("LOCALAPPDATA", root.resolve("Local").toString(), "Path", path)))
                .contains(apps.resolve("wt.exe"));
    }

    @Test
    void noTerminalAnywhereIsNone(@TempDir Path root) {
        assertThat(HostEnvironment.findWindowsTerminal(Map.of("LOCALAPPDATA", root.toString(), "PATH", root.toString())))
                .isEmpty();
    }

    /** The regression itself: on a machine with the Store terminal, the alias Java cannot follow is found. */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void theRealStoreAliasOnThisMachineCounts() {
        Path alias = Path.of(System.getenv("LOCALAPPDATA"), "Microsoft", "WindowsApps", "wt.exe");
        assumeTrue(Files.exists(alias, LinkOption.NOFOLLOW_LINKS), "Windows Terminal from the Store is not installed here");

        assertThat(HostEnvironment.current().terminal()).contains(alias);
    }
}
