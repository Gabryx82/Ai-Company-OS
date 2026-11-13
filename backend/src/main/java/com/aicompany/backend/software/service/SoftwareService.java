package com.aicompany.backend.software.service;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.detect.SoftwareDetector;
import com.aicompany.backend.software.detect.SoftwareIcons;
import com.aicompany.backend.software.exception.SoftwareKeyConflictException;
import com.aicompany.backend.software.exception.SoftwareNotFoundException;
import com.aicompany.backend.software.exception.SoftwareNotLaunchableException;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.launch.SoftwareLauncher;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.model.SoftwareDefinition;
import com.aicompany.backend.software.repository.SoftwareRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The Software Hub (ADR-019). Catalog reads carry a fresh detection; writes
 * follow ADR-009 (row lock, then the precondition); a launch is a read of the
 * catalog followed by a process start, and never writes.
 */
@Service
@Transactional
public class SoftwareService {

    public record Detected(Software software, Detection detection) {
    }

    private final SoftwareRepository repository;
    private final SoftwareDetector detector;
    private final SoftwareLauncher launcher;
    private final SoftwareIcons icons;
    private final ProjectFolders projectFolders;
    private final HostEnvironment host;

    public SoftwareService(SoftwareRepository repository, SoftwareDetector detector, SoftwareLauncher launcher,
                           SoftwareIcons icons, ProjectFolders projectFolders, HostEnvironment environment) {
        this.repository = repository;
        this.detector = detector;
        this.launcher = launcher;
        this.icons = icons;
        this.projectFolders = projectFolders;
        this.host = environment;
    }

    @Transactional(readOnly = true)
    public List<Detected> findAll() {
        List<Software> all = repository.findAllByOrderByCategoryAscNameAsc();
        // ADR-046: the services are asked together, then each entry is read from what they answered.
        detector.prepare(all);
        return all.stream().map(software -> new Detected(software, detector.detect(software))).toList();
    }

    @Transactional(readOnly = true)
    public Detected findByKey(String key) {
        Software software = load(key);
        return new Detected(software, detector.detect(software));
    }

    public Detected create(String key, SoftwareDefinition definition) {
        if (key == null || key.isBlank()) {
            throw new RequestValidationException("key", "is required when creating a catalog entry");
        }
        requireLaunchTarget(definition);
        if (repository.existsByKey(key)) {
            throw new SoftwareKeyConflictException(key);
        }
        try {
            Software saved = repository.saveAndFlush(new Software(key, definition));
            return new Detected(saved, detector.detect(saved));
        } catch (DataIntegrityViolationException raced) {
            // Two creates of the same key: the unique index decided (ADR-004 §4 pattern).
            throw new SoftwareKeyConflictException(key);
        }
    }

    public Detected update(String key, SoftwareDefinition definition, Precondition precondition) {
        requireLaunchTarget(definition);
        Software software = repository.findByKeyForUpdate(key).orElseThrow(() -> new SoftwareNotFoundException(key));
        precondition.requireSatisfiedBy(software.getVersion());
        software.apply(definition);
        repository.flush();
        return new Detected(software, detector.detect(software));
    }

    /**
     * ADR-019 I1/I2: a key and, optionally, a project -- nothing else from the caller.
     * PHASE 33 (ADR-040): with a project, a path names one file of its folder, which the
     * entry opens through its {@code fileArgs}.
     */
    @Transactional(readOnly = true)
    public LaunchPlan launch(String key, Long projectId, String path) {
        if (path != null && !path.isBlank()) {
            if (projectId == null) {
                throw new RequestValidationException("projectId", "is required to open a file: the path is relative to its folder");
            }
            Software software = load(key);
            Path folder = projectFolders.folderOf(projectId).orElseThrow(() -> new SoftwareNotLaunchableException(
                    "Project " + projectId + " has no workspace folder yet"));
            Path file = projectFolders.fileOf(projectId, path);
            LaunchPlan plan = LaunchPlan.forFile(software, detector.detect(software), folder, file, host);
            launcher.start(plan);
            return plan;
        }
        Path folder = null;
        if (projectId != null) {
            folder = projectFolders.folderOf(projectId).orElseThrow(() -> new SoftwareNotLaunchableException(
                    "Project " + projectId + " has no workspace folder yet"));
        }
        return launchIn(key, folder, List.of());
    }

    /**
     * For the orchestrator's handoffs (ADR-021): a CLI started in a project folder
     * with arguments the control plane composed itself. Not reachable from a
     * request body.
     */
    @Transactional(readOnly = true)
    public LaunchPlan launchIn(String key, Path folder, List<String> cliArguments) {
        Software software = load(key);
        LaunchPlan plan = LaunchPlan.of(software, detector.detect(software), folder, cliArguments, host);
        launcher.start(plan);
        return plan;
    }

    /**
     * PHASE 34, for the orchestrator's handoffs: a desktop app opened through the link of
     * its execution target, on a project folder, with a prompt the control plane composed.
     * Not reachable from a request body: the link is the target catalog's.
     */
    @Transactional(readOnly = true)
    public LaunchPlan launchLink(String key, String link, Path folder, String prompt) {
        Software software = load(key);
        LaunchPlan plan = LaunchPlan.forLink(software, detector.detect(software), link, folder, prompt, host);
        launcher.start(plan);
        return plan;
    }

    /** PHASE 36 (ADR-042): why the launcher would open nothing at all, or empty when it opens. */
    @Transactional(readOnly = true)
    public Optional<String> launcherUnavailable() {
        return launcher.unavailable();
    }

    /**
     * PHASE 36: the plan {@link #launchIn} would start, built by the same rules and not started --
     * what a handoff records when the launcher is off.
     */
    @Transactional(readOnly = true)
    public LaunchPlan planIn(String key, Path folder, List<String> cliArguments) {
        Software software = load(key);
        return LaunchPlan.of(software, detector.detect(software), folder, cliArguments, host);
    }

    /** PHASE 36: the plan {@link #launchLink} would start, not started. */
    @Transactional(readOnly = true)
    public LaunchPlan planLink(String key, String link, Path folder, String prompt) {
        Software software = load(key);
        return LaunchPlan.forLink(software, detector.detect(software), link, folder, prompt, host);
    }

    /**
     * PHASE 36 (ADR-042): why a handoff could not open this software, before anything is
     * written -- the same rules {@link #launchIn} and {@link #launchLink} apply, without
     * launching. The project folder is not checked: the handoff prepares it. Empty when it
     * would open.
     */
    @Transactional(readOnly = true)
    public Optional<String> launchProblem(String key, @org.jspecify.annotations.Nullable String link) {
        Optional<Software> found = repository.findByKey(key);
        if (found.isEmpty()) {
            return Optional.of("'" + key + "' is not in the Software Hub");
        }
        Software software = found.get();
        Detection detection = detector.detect(software);
        Path anyFolder = Path.of(System.getProperty("java.io.tmpdir"));
        try {
            if (link != null) {
                LaunchPlan.forLink(software, detection, link, anyFolder, "prompt", host);
            } else {
                LaunchPlan.of(software, detection, null,
                        software.getLaunchKind() == LaunchKind.CLI ? List.of("prompt") : List.of(), host);
            }
            return Optional.empty();
        } catch (com.aicompany.backend.software.exception.SoftwareNotLaunchableException refused) {
            return Optional.of(refused.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public Optional<byte[]> icon(String key) {
        Software software = load(key);
        return icons.icon(software, detector.detect(software));
    }

    @Transactional(readOnly = true)
    public void refreshDetection() {
        detector.refresh();
    }

    /** ADR-046: the local services are asked again at the next detection; the rest is kept. Writes no row. */
    @Transactional(readOnly = true)
    public void refreshServices() {
        detector.forgetServices();
    }

    private Software load(String key) {
        return repository.findByKey(key).orElseThrow(() -> new SoftwareNotFoundException(key));
    }

    /** The same rule as {@code software_launch_target_check}, answered as a 400 instead of a 500. */
    private static void requireLaunchTarget(SoftwareDefinition d) {
        // PHASE 33 (ADR-040): software_file_args_check, and the placeholders whole.
        LaunchPlan.fileArgumentsProblem(d.launchKind(), d.executable(), d.fileArgs()).ifPresent(problem -> {
            throw new RequestValidationException("fileArgs", problem);
        });
        switch (d.launchKind()) {
            case DESKTOP -> {
                if (blank(d.appId()) && blank(d.executable())) {
                    throw new RequestValidationException("executable", "a desktop entry needs an appId or an executable");
                }
            }
            case CLI -> {
                if (blank(d.cliCommand())) {
                    throw new RequestValidationException("cliCommand", "a command-line entry needs its command");
                }
            }
            case WEB, LOCAL_SERVICE -> {
                if (blank(d.url())) {
                    throw new RequestValidationException("url", "a web or local-service entry needs its URL");
                }
            }
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
