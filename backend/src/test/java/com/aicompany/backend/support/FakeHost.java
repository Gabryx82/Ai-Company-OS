package com.aicompany.backend.support;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.detect.SoftwareDetector;
import com.aicompany.backend.software.detect.SoftwareIcons;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.launch.SoftwareLauncher;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A machine described by the test instead of the real one: detection answers
 * what the test scripted, launches are recorded and never started, icons are
 * absent. No test runs PowerShell or starts a process.
 */
public class FakeHost {

    /** The three host-facing roles, as separate objects so that only one bean has the FakeHost type. */
    public SoftwareDetector detector() {
        return new SoftwareDetector() {
            @Override
            public Detection detect(Software software) {
                return FakeHost.this.detect(software);
            }

            @Override
            public void refresh() {
                FakeHost.this.refresh();
            }
        };
    }

    public SoftwareLauncher launcher() {
        return this::start;
    }

    public SoftwareIcons icons() {
        return this::icon;
    }


    /** The executable every "installed" desktop entry resolves to, unless scripted otherwise. */
    public static final Path FAKE_EXECUTABLE = Path.of("C:\\Fake\\program.exe");

    private final Map<String, Detection> scripted = new ConcurrentHashMap<>();
    private final List<LaunchPlan> launches = new ArrayList<>();
    private volatile int refreshes;

    public synchronized void reset() {
        scripted.clear();
        launches.clear();
        refreshes = 0;
    }

    public void script(String key, Detection detection) {
        scripted.put(key, detection);
    }

    public synchronized List<LaunchPlan> launches() {
        return List.copyOf(launches);
    }

    public int refreshes() {
        return refreshes;
    }

    public Detection detect(Software software) {
        Detection detection = scripted.get(software.getKey());
        if (detection != null) {
            return detection;
        }
        if (software.getIncompatibleReason() != null) {
            return Detection.of(Availability.INCOMPATIBLE_HARDWARE, software.getIncompatibleReason());
        }
        return switch (software.getLaunchKind()) {
            case WEB -> Detection.of(Availability.WEB, null);
            case LOCAL_SERVICE -> Detection.of(Availability.STOPPED, "scripted: not running");
            case CLI -> new Detection(Availability.INSTALLED, null, Path.of("C:\\Fake\\" + software.getCliCommand()));
            case DESKTOP -> software.getLaunchKind() == LaunchKind.DESKTOP && software.getExecutable() != null
                    ? new Detection(Availability.INSTALLED, null, FAKE_EXECUTABLE)
                    : Detection.of(Availability.INSTALLED, "Start menu: " + software.getAppId());
        };
    }

    public void refresh() {
        refreshes++;
    }

    public synchronized void start(LaunchPlan plan) {
        launches.add(plan);
    }

    public Optional<byte[]> icon(Software software, Detection detection) {
        return Optional.empty();
    }
}
