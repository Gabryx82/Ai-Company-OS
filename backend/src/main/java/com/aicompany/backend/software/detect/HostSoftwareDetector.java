package com.aicompany.backend.software.detect;

import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Detection on the real machine (ADR-019 §3).
 *
 * <ul>
 *   <li>one {@code Get-StartApps} call, through Windows PowerShell by absolute
 *       path -- {@code powershell} is not on the PATH of every process, which is
 *       exactly how {@code start-dev.ps1} once failed (commit {@code cf97c0d});</li>
 *   <li>the executable template resolved on disk;</li>
 *   <li>the CLI command looked up on the PATH;</li>
 *   <li>an HTTP probe for local services.</li>
 * </ul>
 *
 * Observations are cached for {@link #TTL}: detection runs on every catalog
 * read, and PowerShell takes about a second to start.
 *
 * <p>ADR-046: a catalog read asks its services together ({@link #prepare}), so it
 * takes the time of the slowest answer and not their sum; the Start menu's list is
 * kept for {@link #START_APPS_TTL} and, once known, renewed in the background --
 * applications are rarely installed, and a page should not wait for PowerShell.
 */
public class HostSoftwareDetector implements SoftwareDetector {

    private static final Logger log = LoggerFactory.getLogger(HostSoftwareDetector.class);

    static final Duration TTL = Duration.ofSeconds(60);
    static final Duration START_APPS_TTL = Duration.ofMinutes(10);

    private final Map<String, String> environment;
    private final boolean windows;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(800))
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final JsonMapper json = JsonMapper.builder().build();

    private volatile Set<String> startApps;
    private volatile Instant startAppsAt = Instant.MIN;
    private final java.util.concurrent.atomic.AtomicBoolean startAppsLoading = new java.util.concurrent.atomic.AtomicBoolean();
    /** {@link #refresh()} asked to look again: the next catalog read loads the Start menu alongside its probes. */
    private volatile boolean startAppsAsked;
    private final Map<String, Probe> probes = new ConcurrentHashMap<>();

    private record Probe(boolean up, Instant at) {
    }

    /**
     * ADR-046: where an executable or a command is, kept like a probe. Looked for at every catalog
     * read, a command cost a disk check for every folder of the PATH and every extension.
     */
    private record Lookup(Optional<Path> path, Instant at) {
    }

    private final Map<String, Lookup> lookups = new ConcurrentHashMap<>();

    public HostSoftwareDetector(Map<String, String> environment, boolean windows, Clock clock) {
        this.environment = Map.copyOf(environment);
        this.windows = windows;
        this.clock = clock;
    }

    @Override
    public Detection detect(Software software) {
        if (software.getIncompatibleReason() != null) {
            return Detection.of(Availability.INCOMPATIBLE_HARDWARE, software.getIncompatibleReason());
        }
        return switch (software.getLaunchKind()) {
            case WEB -> Detection.of(Availability.WEB, null);
            case CLI -> detectCli(software);
            case DESKTOP -> detectInstalled(software)
                    .orElseGet(() -> Detection.of(notFound(), "Not found on this machine"));
            case LOCAL_SERVICE -> detectService(software);
        };
    }

    @Override
    public void refresh() {
        startAppsAsked = true;
        startAppsAt = Instant.MIN;
        probes.clear();
        lookups.clear();
    }

    @Override
    public void forgetServices() {
        probes.clear();
    }

    private Optional<Path> located(String key, java.util.function.Supplier<Optional<Path>> find) {
        Instant now = clock.instant();
        Lookup cached = lookups.get(key);
        if (cached != null && cached.at().plus(TTL).isAfter(now)) {
            return cached.path();
        }
        Optional<Path> found = find.get();
        lookups.put(key, new Lookup(found, now));
        return found;
    }

    /**
     * Asks every local service whose answer is no longer kept, all at once, and loads the
     * Start menu's list alongside when it was never loaded: then {@link #detect} finds them kept.
     */
    @Override
    public void prepare(java.util.Collection<Software> all) {
        Instant now = clock.instant();
        Set<String> urls = new java.util.LinkedHashSet<>();
        boolean desktop = false;
        for (Software software : all) {
            if (software.getIncompatibleReason() != null) {
                continue;
            }
            if (software.getLaunchKind() == LaunchKind.LOCAL_SERVICE) {
                String url = probeUrl(software);
                Probe cached = probes.get(url);
                if (cached == null || !cached.at().plus(TTL).isAfter(now)) {
                    urls.add(url);
                }
            }
            desktop |= software.getAppId() != null;
        }
        boolean loadStartMenu = desktop && windows && (startApps == null || startAppsAsked);
        if (urls.isEmpty() && !loadStartMenu) {
            return;
        }
        try (var together = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            urls.forEach(url -> together.submit(() -> probe(url)));
            if (loadStartMenu) {
                together.submit(this::reloadStartApps);
            }
        }
    }

    private Availability notFound() {
        return windows ? Availability.NOT_INSTALLED : Availability.UNKNOWN;
    }

    private Optional<Detection> detectInstalled(Software software) {
        if (software.getExecutable() != null) {
            // A bare command ("docker") is looked up on the PATH, wherever it was installed.
            String template = software.getExecutable().strip();
            Optional<Path> executable = isBareCommand(template) ? located("path:" + template, () -> onPath(template))
                    : located("file:" + template, () -> PathTemplate.resolveFile(template, environment));
            if (executable.isPresent()) {
                return Optional.of(new Detection(Availability.INSTALLED, null, executable.get()));
            }
        }
        if (software.getAppId() != null && startApps().contains(software.getAppId().toLowerCase(Locale.ROOT))) {
            return Optional.of(Detection.of(Availability.INSTALLED, "Start menu: " + software.getAppId()));
        }
        return Optional.empty();
    }

    private Detection detectCli(Software software) {
        String command = software.getCliCommand().strip().split("\\s+")[0];
        return located("path:" + command, () -> onPath(command))
                .map(path -> new Detection(Availability.INSTALLED, null, path))
                .orElseGet(() -> Detection.of(notFound(), "'" + command + "' is not on the PATH"));
    }

    private static String probeUrl(Software software) {
        return software.getHealthUrl() != null ? software.getHealthUrl() : software.getUrl();
    }

    private Detection detectService(Software software) {
        String probeUrl = probeUrl(software);
        boolean up = probe(probeUrl);
        Optional<Detection> installed = detectInstalled(software);
        if (up) {
            return new Detection(Availability.RUNNING, probeUrl,
                    installed.map(Detection::executable).orElse(null));
        }
        if (installed.isPresent() || (software.getExecutable() == null && software.getAppId() == null)) {
            return new Detection(Availability.STOPPED, "Nothing answers at " + probeUrl,
                    installed.map(Detection::executable).orElse(null));
        }
        return Detection.of(notFound(), "Not found on this machine, and nothing answers at " + probeUrl);
    }

    private static boolean isBareCommand(String executable) {
        return !executable.contains("\\") && !executable.contains("/") && !executable.contains("%")
                && !executable.contains(":") && !executable.contains(" ");
    }

    Optional<Path> onPath(String command) {
        if (command.contains("\\") || command.contains("/")) {
            return PathTemplate.resolveFile(command, environment);
        }
        String path = lookup("PATH");
        if (path == null) {
            return Optional.empty();
        }
        List<String> extensions = windows
                ? List.of(Optional.ofNullable(lookup("PATHEXT")).orElse(".COM;.EXE;.BAT;.CMD").split(";"))
                : List.of("");
        for (String directory : path.split(File.pathSeparator)) {
            if (directory.isBlank()) {
                continue;
            }
            for (String extension : extensions) {
                Path candidate = Path.of(directory.strip(), command + extension.toLowerCase(Locale.ROOT));
                if (Files.isRegularFile(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }

    private String lookup(String name) {
        return environment.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
    }

    private boolean probe(String url) {
        Instant now = clock.instant();
        Probe cached = probes.get(url);
        if (cached != null && cached.at().plus(TTL).isAfter(now)) {
            return cached.up();
        }
        boolean up;
        try {
            HttpResponse<Void> response = http.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(1500)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            up = response.statusCode() < 500;
        } catch (Exception unreachable) {
            if (unreachable instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            up = false;
        }
        probes.put(url, new Probe(up, now));
        return up;
    }

    private Set<String> startApps() {
        if (!windows) {
            return Set.of();
        }
        Instant now = clock.instant();
        Set<String> cached = startApps;
        if (cached != null) {
            // Known: answered at once; an old list is renewed in the background for the next look.
            if (!startAppsAt.plus(START_APPS_TTL).isAfter(now) && startAppsLoading.compareAndSet(false, true)) {
                Thread.ofVirtual().name("aicos-start-apps").start(() -> {
                    try {
                        Set<String> loaded = loadStartApps();
                        startApps = loaded;
                        startAppsAt = clock.instant();
                    } finally {
                        startAppsLoading.set(false);
                    }
                });
            }
            return cached;
        }
        synchronized (this) {
            if (startApps != null) {
                return startApps;
            }
            return reloadStartApps();
        }
    }

    private synchronized Set<String> reloadStartApps() {
        startAppsAsked = false;
        Set<String> loaded = loadStartApps();
        startApps = loaded;
        startAppsAt = clock.instant();
        return loaded;
    }

    private Set<String> loadStartApps() {
        Optional<String> powershell = PathTemplate.expand(
                "%SystemRoot%\\System32\\WindowsPowerShell\\v1.0\\powershell.exe", environment);
        if (powershell.isEmpty()) {
            return Set.of();
        }
        try {
            Process process = new ProcessBuilder(powershell.get(), "-NoProfile", "-NonInteractive", "-Command",
                    "[Console]::OutputEncoding=[Text.Encoding]::UTF8; "
                            + "Get-StartApps | Select-Object -ExpandProperty AppID | ConvertTo-Json -Compress")
                    .redirectErrorStream(false)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] output = process.getInputStream().readAllBytes();
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Set.of();
            }
            JsonNode ids = json.readTree(new String(output, StandardCharsets.UTF_8));
            Set<String> result = new HashSet<>();
            if (ids.isArray()) {
                ids.forEach(id -> result.add(id.asString().toLowerCase(Locale.ROOT)));
            } else if (ids.isString()) {
                result.add(ids.asString().toLowerCase(Locale.ROOT));
            }
            return result;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Start menu applications could not be listed: {}", failure.toString());
            return Set.of();
        }
    }
}
