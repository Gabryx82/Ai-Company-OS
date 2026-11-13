package com.aicompany.backend.software.launch;

import com.aicompany.backend.software.exception.LaunchFailedException;
import com.aicompany.backend.software.exception.LauncherUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * The real launcher. A {@link ProcessBuilder} with separate arguments -- no
 * shell, so nothing in an argument is ever parsed as a command (ADR-019 I3) --
 * and its output discarded, so a chatty program cannot fill a pipe nobody reads.
 */
public class ProcessSoftwareLauncher implements SoftwareLauncher {

    private static final Logger log = LoggerFactory.getLogger(ProcessSoftwareLauncher.class);

    private final java.util.Optional<String> unavailable;

    /** PHASE 36: off by configuration and off because the host is not Windows are two different reasons. */
    public ProcessSoftwareLauncher(boolean enabled, boolean windows) {
        this.unavailable = !enabled
                ? java.util.Optional.of("The launcher is switched off on this control plane (aicos.launcher.enabled=false)")
                : !windows ? java.util.Optional.of("Launching local software is supported only when the control plane runs on Windows")
                : java.util.Optional.empty();
    }

    @Override
    public java.util.Optional<String> unavailable() {
        return unavailable;
    }

    @Override
    public void start(LaunchPlan plan) {
        if (unavailable.isPresent()) {
            throw new LauncherUnavailableException(unavailable.get());
        }
        try {
            new ProcessBuilder(plan.command())
                    .directory(plan.workingDirectory().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            log.info("Launched {}", plan.command().getFirst());
        } catch (IOException | RuntimeException e) {
            log.warn("Launch of {} failed: {}", plan.command().getFirst(), e.toString());
            throw new LaunchFailedException("The operating system did not start " + plan.command().getFirst());
        }
    }
}
