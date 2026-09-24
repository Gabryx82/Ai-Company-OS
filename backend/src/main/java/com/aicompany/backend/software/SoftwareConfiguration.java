package com.aicompany.backend.software;

import com.aicompany.backend.software.detect.HostSoftwareDetector;
import com.aicompany.backend.software.detect.HostSoftwareIcons;
import com.aicompany.backend.software.detect.SoftwareDetector;
import com.aicompany.backend.software.detect.SoftwareIcons;
import com.aicompany.backend.software.launch.ProcessSoftwareLauncher;
import com.aicompany.backend.software.launch.SoftwareLauncher;
import com.aicompany.backend.software.service.HostEnvironment;
import com.aicompany.backend.software.service.ProjectFolders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.Optional;

/**
 * The host-facing beans of the Software Hub. Tests replace all of them with
 * {@code @Primary} fakes: no test runs PowerShell or starts a process.
 *
 * <p>{@code aicos.launcher.enabled} (default {@code true}) switches the real
 * launcher off without touching detection -- ADR-019 I6.
 */
@Configuration(proxyBeanMethods = false)
public class SoftwareConfiguration {

    @Bean
    HostEnvironment hostEnvironment() {
        return HostEnvironment.current();
    }

    @Bean
    SoftwareDetector softwareDetector(HostEnvironment host) {
        return new HostSoftwareDetector(host.variables(), host.windows(), Clock.systemUTC());
    }

    @Bean
    SoftwareIcons softwareIcons(HostEnvironment host) {
        return new HostSoftwareIcons(host.variables(), host.windows());
    }

    @Bean
    SoftwareLauncher softwareLauncher(HostEnvironment host,
                                      @Value("${aicos.launcher.enabled:true}") boolean enabled) {
        return new ProcessSoftwareLauncher(enabled && host.windows());
    }

    /** Until a project workspace exists (PHASE 9) no project has a folder. */
    @Bean
    ProjectFolders noProjectFolders() {
        return projectId -> Optional.empty();
    }
}
