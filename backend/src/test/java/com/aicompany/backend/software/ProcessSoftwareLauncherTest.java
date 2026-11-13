package com.aicompany.backend.software;

import com.aicompany.backend.software.exception.LauncherUnavailableException;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.launch.ProcessSoftwareLauncher;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PHASE 36 (ADR-042): a launcher that cannot open anything says why, before it is asked to. */
class ProcessSoftwareLauncherTest {

    @Test
    void offByConfigurationAndOffTheWindowsHostAreTwoReasons() {
        assertThat(new ProcessSoftwareLauncher(false, true).unavailable()).get().asString().contains("aicos.launcher.enabled=false");
        assertThat(new ProcessSoftwareLauncher(true, false).unavailable()).get().asString().contains("runs on Windows");
        assertThat(new ProcessSoftwareLauncher(true, true).unavailable()).isEmpty();
    }

    @Test
    void anUnavailableLauncherRefusesWithItsReason() {
        LaunchPlan plan = new LaunchPlan(List.of("C:\nothing.exe"), Path.of("."), false, null);
        assertThatThrownBy(() -> new ProcessSoftwareLauncher(false, true).start(plan))
                .isInstanceOf(LauncherUnavailableException.class).hasMessageContaining("switched off");
    }
}
