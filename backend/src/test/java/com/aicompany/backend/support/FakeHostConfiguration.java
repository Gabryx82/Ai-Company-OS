package com.aicompany.backend.support;

import com.aicompany.backend.software.detect.SoftwareDetector;
import com.aicompany.backend.software.detect.SoftwareIcons;
import com.aicompany.backend.software.launch.SoftwareLauncher;
import com.aicompany.backend.software.service.HostEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@TestConfiguration(proxyBeanMethods = false)
public class FakeHostConfiguration {

    private static final FakeHost HOST = new FakeHost();

    /**
     * A Windows-like machine in a temporary folder: a home, and a Windows
     * Terminal that exists as a file -- so launch planning runs the same on the
     * Linux CI runner as on the operator's machine.
     */
    @Bean
    @Primary
    HostEnvironment fakeHostEnvironment() throws IOException {
        Path root = Files.createTempDirectory("aicos-fake-host");
        Path terminal = Files.createDirectories(root.resolve("WindowsApps")).resolve("wt.exe");
        Files.writeString(terminal, "");
        Path home = Files.createDirectories(root.resolve("home"));
        return new HostEnvironment(Map.of("SystemRoot", "C:\\Windows", "USERPROFILE", home.toString()),
                true, terminal, home);
    }

    @Bean
    FakeHost fakeHost() {
        return HOST;
    }

    @Bean
    @Primary
    SoftwareDetector fakeDetector() {
        return HOST.detector();
    }

    @Bean
    @Primary
    SoftwareLauncher fakeLauncher() {
        return HOST.launcher();
    }

    @Bean
    @Primary
    SoftwareIcons fakeIcons() {
        return HOST.icons();
    }

    private static final FakeRemoteText WEB = new FakeRemoteText();

    /** PHASE 27: which executables the fake machine has running; nothing unless a test says so. */
    public static final java.util.Set<java.nio.file.Path> RUNNING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Bean
    @Primary
    com.aicompany.backend.ecosystem.ProcessProbe fakeProcessProbe() {
        return new com.aicompany.backend.ecosystem.ProcessProbe() {
            @Override
            public boolean isRunning(java.nio.file.Path executable) {
                return executable != null && RUNNING.contains(executable);
            }
        };
    }

    /** PHASE 19: the skill import never reaches the network in tests. */
    @Bean
    @Primary
    FakeRemoteText fakeRemoteText() {
        return WEB;
    }
}
