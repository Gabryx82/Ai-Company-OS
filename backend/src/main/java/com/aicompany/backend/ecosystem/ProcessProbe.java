package com.aicompany.backend.ecosystem;

import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * Whether a program is already running, by its executable (ADR-028 §1): the
 * second half of "never start twice" -- an app can be open while its service
 * does not answer yet (Open WebUI's desktop app, found in the PHASE 27 smoke).
 * Generic launchers (PowerShell, cmd, …) say nothing about what they launch and
 * are never taken as evidence.
 */
@Component
public class ProcessProbe {

    static final Set<String> GENERIC = Set.of("powershell.exe", "pwsh.exe", "cmd.exe", "wscript.exe", "cscript.exe",
            "explorer.exe", "python.exe", "pythonw.exe", "node.exe", "java.exe", "javaw.exe");

    public boolean isRunning(Path executable) {
        if (executable == null || GENERIC.contains(executable.getFileName().toString().toLowerCase(Locale.ROOT))) {
            return false;
        }
        String wanted = executable.toAbsolutePath().normalize().toString();
        return ProcessHandle.allProcesses().anyMatch(p -> p.info().command()
                .map(c -> c.equalsIgnoreCase(wanted)).orElse(false));
    }
}
