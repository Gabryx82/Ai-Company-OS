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
        return repository.findAllByOrderByCategoryAscNameAsc().stream()
                .map(software -> new Detected(software, detector.detect(software)))
                .toList();
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

    /** ADR-019 I1/I2: a key and, optionally, a project -- nothing else from the caller. */
    @Transactional(readOnly = true)
    public LaunchPlan launch(String key, Long projectId) {
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

    @Transactional(readOnly = true)
    public Optional<byte[]> icon(String key) {
        Software software = load(key);
        return icons.icon(software, detector.detect(software));
    }

    @Transactional(readOnly = true)
    public void refreshDetection() {
        detector.refresh();
    }

    private Software load(String key) {
        return repository.findByKey(key).orElseThrow(() -> new SoftwareNotFoundException(key));
    }

    /** The same rule as {@code software_launch_target_check}, answered as a 400 instead of a 500. */
    private static void requireLaunchTarget(SoftwareDefinition d) {
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
