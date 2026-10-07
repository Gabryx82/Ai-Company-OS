package com.aicompany.backend.harness.hub;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Runs {@code claude} and {@code codex} for real: found in their documented
 * install places or on the PATH (or where {@code aicos.hub.claude-cli} and
 * {@code aicos.hub.codex-cli} say), started with an argument list -- a
 * {@code .cmd} wrapper through {@code cmd /c} -- with stdin closed, a timeout,
 * and at most {@value #MAX_OUTPUT} bytes of output kept.
 */
@Component
class ProcessHubCommands implements HubCommands {

    static final int MAX_OUTPUT = 64 * 1024;

    private final Path home;
    private final Path localAppData;
    private final String claudeCli;
    private final String codexCli;

    ProcessHubCommands(@Value("${aicos.hub.home:${user.home}}") String home,
                       @Value("${aicos.hub.local-app-data:}") String localAppData,
                       @Value("${aicos.hub.claude-cli:}") String claudeCli,
                       @Value("${aicos.hub.codex-cli:}") String codexCli) {
        this.home = Path.of(home).toAbsolutePath().normalize();
        this.localAppData = localAppData == null || localAppData.isBlank()
                ? Optional.ofNullable(System.getenv("LOCALAPPDATA")).map(Path::of)
                        .orElse(this.home.resolve("AppData").resolve("Local"))
                : Path.of(localAppData);
        this.claudeCli = claudeCli;
        this.codexCli = codexCli;
    }

    @Override
    public Optional<Path> executable(Tool tool) {
        String configured = tool == Tool.CLAUDE ? claudeCli : codexCli;
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
        }
        List<Path> candidates = new ArrayList<>();
        if (tool == Tool.CLAUDE) {
            candidates.add(home.resolve(".local").resolve("bin").resolve("claude.exe"));
            candidates.add(home.resolve(".local").resolve("bin").resolve("claude"));
        } else {
            // The Codex app keeps its CLI in a versioned folder: the newest one.
            Path bin = localAppData.resolve("OpenAI").resolve("Codex").resolve("bin");
            if (Files.isDirectory(bin, LinkOption.NOFOLLOW_LINKS)) {
                try (Stream<Path> list = Files.list(bin)) {
                    list.map(p -> p.resolve("codex.exe")).filter(Files::isRegularFile)
                            .max(Comparator.comparingLong(ProcessHubCommands::modified)).ifPresent(candidates::add);
                } catch (IOException e) {
                    // fall back to the PATH
                }
            }
        }
        String name = tool == Tool.CLAUDE ? "claude" : "codex";
        for (String dir : Optional.ofNullable(System.getenv("PATH")).orElse("").split(java.io.File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String suffix : List.of(".exe", ".cmd", "")) {
                try {
                    candidates.add(Path.of(dir.strip(), name + suffix));
                } catch (RuntimeException e) {
                    // an invalid PATH entry
                }
            }
        }
        return candidates.stream().filter(p -> Files.isRegularFile(p)).findFirst();
    }

    @Override
    public Result run(Tool tool, List<String> arguments, Duration timeout) {
        Path executable = executable(tool).orElseThrow(() -> HubProblemException.readOnly(
                (tool == Tool.CLAUDE ? "Claude Code (la CLI «claude»)" : "Codex (la CLI «codex»)")
                        + " non è installato su questo computer"));
        List<String> command = new ArrayList<>();
        String file = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean viaShell = file.endsWith(".cmd") || file.endsWith(".bat");
        requireSafe(arguments, viaShell);
        if (viaShell) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(executable.toString());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .directory(home.toFile());
        builder.redirectInput(ProcessBuilder.Redirect.from(nullDevice()));
        try {
            Process process = builder.start();
            long deadline = System.nanoTime() + timeout.toNanos();
            byte[] output;
            try (InputStream in = process.getInputStream()) {
                output = readCapped(in, process, deadline);
            }
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished || System.nanoTime() > deadline && process.exitValue() != 0) {
                process.destroyForcibly();
                return new Result(-1, new String(output, StandardCharsets.UTF_8), true);
            }
            return new Result(process.exitValue(), new String(output, StandardCharsets.UTF_8), false);
        } catch (IOException e) {
            throw HubProblemException.marketplace("Il comando non è partito: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw HubProblemException.marketplace("Il comando è stato interrotto");
        }
    }

    /**
     * Every argument reaches the CLI as one argument, or nothing runs: no quote or
     * control character anywhere; and when the CLI is a {@code .cmd} (Claude Code
     * installed with npm), which {@code cmd.exe} reads as a command line, none of
     * the characters it would interpret either.
     */
    static void requireSafe(List<String> arguments, boolean viaShell) {
        for (String argument : arguments) {
            if (argument.chars().anyMatch(c -> c < 0x20 || c == '"')) {
                throw HubProblemException.packageRefused("Un valore contiene virgolette o caratteri di controllo: va configurato a mano");
            }
            if (viaShell && argument.chars().anyMatch(c -> "&|<>^%".indexOf(c) >= 0)) {
                throw HubProblemException.packageRefused("Un valore contiene caratteri (& | < > ^ %) che il prompt dei comandi "
                        + "interpreterebbe: con questa CLI va aggiunto a mano");
            }
        }
    }

    /** Reads the output while the process runs; past the timeout the process is stopped. */
    private static byte[] readCapped(InputStream in, Process process, long deadline) throws IOException, InterruptedException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            if (in.available() > 0) {
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                if (out.size() < MAX_OUTPUT) {
                    out.write(buffer, 0, Math.min(n, MAX_OUTPUT - out.size()));
                }
                continue;
            }
            if (!process.isAlive()) {
                byte[] rest = in.readAllBytes();
                if (out.size() < MAX_OUTPUT) {
                    out.write(rest, 0, Math.min(rest.length, MAX_OUTPUT - out.size()));
                }
                break;
            }
            if (System.nanoTime() > deadline) {
                process.destroyForcibly();
                break;
            }
            Thread.sleep(50);
        }
        return out.toByteArray();
    }

    private static java.io.File nullDevice() {
        return new java.io.File(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "NUL" : "/dev/null");
    }

    private static long modified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }
}
