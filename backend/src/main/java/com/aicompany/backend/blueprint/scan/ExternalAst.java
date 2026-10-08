package com.aicompany.backend.blueprint.scan;

import com.aicompany.backend.blueprint.scan.BlueprintLanguagePlugin.SourceFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * PHASE 32 (TD-57): the languages' own parsers, run once per scan -- TypeScript's
 * compiler under Node.js, Python's {@code ast} -- on every file of that language,
 * through a script shipped with AI Company OS that only <em>parses</em>: nothing of
 * the project is executed. The files go in on stdin, the facts come back as JSON.
 * When the runtime or the parser is not on this machine, or it fails, the answer is
 * empty and the plugin reads the files lexically, as before.
 */
final class ExternalAst {

    private static final Logger log = LoggerFactory.getLogger(ExternalAst.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration TIMEOUT = Duration.ofSeconds(90);
    private static final int MAX_OUTPUT = 64 * 1024 * 1024;
    private static final Map<String, Path> SCRIPTS = new ConcurrentHashMap<>();

    private ExternalAst() {
    }

    /** What a run said: per file, its facts; and the parser that read them. */
    record Facts(Map<String, JsonNode> files, String parser) {
    }

    /**
     * Runs {@code command} followed by the shipped {@code script} (a classpath
     * resource), with the files as a JSON array on stdin. Empty when anything goes
     * wrong -- the caller falls back to its lexical reading.
     */
    static Optional<Facts> run(List<String> command, String script, Map<String, String> environment, List<SourceFile> files) {
        if (files.isEmpty()) {
            return Optional.empty();
        }
        Process process = null;
        try {
            Path scriptFile = script(script);
            List<String> full = new java.util.ArrayList<>(command);
            full.add(scriptFile.toString());
            ProcessBuilder builder = new ProcessBuilder(full).redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().putAll(environment);
            process = builder.start();
            ArrayNode input = JSON.createArrayNode();
            files.forEach(f -> input.addObject().put("path", f.path()).put("text", f.text()));
            byte[] body = JSON.writeValueAsBytes(input);
            Process running = process;
            Thread writer = Thread.ofVirtual().start(() -> {
                try (OutputStream in = running.getOutputStream()) {
                    in.write(body);
                } catch (IOException ignored) {
                    // the process ended early: its exit code says so
                }
            });
            byte[] output = readCapped(process.getInputStream());
            if (!process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS) || process.exitValue() != 0) {
                log.info("Blueprint: {} did not parse the files (exit {}); reading them lexically", command.getFirst(),
                        process.isAlive() ? "timeout" : process.exitValue());
                return Optional.empty();
            }
            writer.join(Duration.ofSeconds(5));
            JsonNode result = JSON.readTree(new String(output, StandardCharsets.UTF_8));
            Map<String, JsonNode> byFile = new HashMap<>();
            result.path("files").properties().forEach(e -> byFile.put(e.getKey(), e.getValue()));
            return Optional.of(new Facts(byFile, result.path("parser").asString("")));
        } catch (IOException | RuntimeException e) {
            log.info("Blueprint: {} is not available here ({}); reading the files lexically", command.getFirst(), e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    /** The shipped script, copied once per JVM to a temporary file the runtime can read. */
    private static Path script(String resource) throws IOException {
        Path existing = SCRIPTS.get(resource);
        if (existing != null && Files.isRegularFile(existing)) {
            return existing;
        }
        try (InputStream in = ExternalAst.class.getResourceAsStream("/blueprint/" + resource)) {
            if (in == null) {
                throw new IOException("missing resource " + resource);
            }
            String suffix = resource.substring(resource.lastIndexOf('.'));
            Path file = Files.createTempFile("aicos-blueprint-", suffix);
            Files.write(file, in.readAllBytes());
            file.toFile().deleteOnExit();
            SCRIPTS.put(resource, file);
            return file;
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (out.size() + n > MAX_OUTPUT) {
                throw new IOException("parser output too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    /** An executable: the variable's value when set, else the default (looked up on the PATH by the system). */
    static String executable(String variable, String fallback) {
        String value = System.getenv(variable);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    /**
     * Where TypeScript's compiler is: {@code AICOS_TYPESCRIPT}, else the console's own
     * copy next to the backend (the repository's {@code frontend/node_modules}).
     */
    static Optional<Path> typescriptModule() {
        String configured = System.getenv("AICOS_TYPESCRIPT");
        List<Path> candidates = configured != null && !configured.isBlank() ? List.of(Path.of(configured.strip()))
                : List.of(Path.of("../frontend/node_modules/typescript"), Path.of("frontend/node_modules/typescript"));
        return candidates.stream().map(p -> p.toAbsolutePath().normalize())
                .filter(p -> Files.isRegularFile(p.resolve("lib").resolve("typescript.js"))).findFirst();
    }
}
