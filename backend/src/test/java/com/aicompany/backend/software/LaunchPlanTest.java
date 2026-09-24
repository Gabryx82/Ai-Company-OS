package com.aicompany.backend.software;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.exception.SoftwareNotLaunchableException;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.model.SoftwareCategory;
import com.aicompany.backend.software.model.SoftwareDefinition;
import com.aicompany.backend.software.service.HostEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ADR-019 §4: the command line of a launch comes from the catalog, and the
 * caller can contribute exactly one folder that the database resolved. These
 * tests pin each invariant separately, because each one closes a different way
 * of running a program nobody catalogued.
 */
class LaunchPlanTest {

    @TempDir
    Path root;

    private HostEnvironment host;
    private Path terminal;
    private Path project;

    @BeforeEach
    void machine() throws IOException {
        terminal = Files.createDirectories(root.resolve("WindowsApps")).resolve("wt.exe");
        Files.writeString(terminal, "");
        project = Files.createDirectories(root.resolve("projects").resolve("demo"));
        host = new HostEnvironment(Map.of("SystemRoot", "C:\\Windows", "USERPROFILE", root.toString()), true,
                terminal, root);
    }

    private static Software entry(LaunchKind kind, String appId, String executable, boolean openFolder,
                                  String cli, String url, List<String> args, String incompatible, Boolean enabled) {
        return new Software("demo", new SoftwareDefinition("Demo", SoftwareCategory.IDE, "role", null, List.of(),
                List.of(), kind, appId, executable, args, openFolder, cli, url, null, false, null, false, null, null,
                null, incompatible, enabled));
    }

    private static Software desktop(String appId, String executable, boolean openFolder) {
        return entry(LaunchKind.DESKTOP, appId, executable, openFolder, null, null, List.of(), null, true);
    }

    private static final Detection INSTALLED_EXE =
            new Detection(Availability.INSTALLED, null, Path.of("C:\\Apps\\ide.exe"));

    // --- I1: the program is the catalog's ---------------------------------------

    @Test
    void aDesktopEntryRunsTheExecutableDetectionResolved() {
        LaunchPlan plan = LaunchPlan.of(desktop(null, "%ProgramFiles%\\X\\ide.exe", false), INSTALLED_EXE, null,
                List.of(), host);

        assertThat(plan.command()).containsExactly("C:\\Apps\\ide.exe");
        assertThat(plan.workingDirectory()).isEqualTo(root);
    }

    @Test
    void aStoreEntryOpensThroughTheShellWithItsAppIdAndNothingElse() {
        LaunchPlan plan = LaunchPlan.of(desktop("OpenAI.Codex_2p2nqsd0c76g0!App", null, false),
                Detection.of(Availability.INSTALLED, "Start menu"), null, List.of(), host);

        assertThat(plan.command()).containsExactly("C:\\Windows\\explorer.exe", "shell:AppsFolder\\OpenAI.Codex_2p2nqsd0c76g0!App");
    }

    @Test
    void catalogArgumentsAreExpandedAndKeptInOrder() {
        Software service = entry(LaunchKind.LOCAL_SERVICE, null, "%SystemRoot%\\ps.exe", false, null,
                "http://localhost:8800", List.of("-File", "%USERPROFILE%\\start.ps1"), null, true);

        LaunchPlan plan = LaunchPlan.of(service, new Detection(Availability.STOPPED, null, Path.of("C:\\ps.exe")),
                null, List.of(), host);

        assertThat(plan.command()).containsExactly("C:\\ps.exe", "-File", root + "\\start.ps1");
    }

    @Test
    void anEntryNothingDetectedIsNotLaunched() {
        assertThatThrownBy(() -> LaunchPlan.of(desktop("Some.App", null, false),
                Detection.of(Availability.NOT_INSTALLED, "absent"), null, List.of(), host))
                .isInstanceOf(SoftwareNotLaunchableException.class)
                .hasMessageContaining("not installed");
    }

    // --- I2: one folder, from the database, that exists --------------------------

    @Test
    void theFolderIsTheOnlyVariableArgumentAndOnlyForEntriesThatOpenFolders() {
        LaunchPlan opens = LaunchPlan.of(desktop(null, "ide.exe", true), INSTALLED_EXE, project, List.of(), host);
        LaunchPlan ignores = LaunchPlan.of(desktop(null, "ide.exe", false), INSTALLED_EXE, project, List.of(), host);

        assertThat(opens.command()).containsExactly("C:\\Apps\\ide.exe", project.toString());
        assertThat(opens.folderOpened()).isTrue();
        assertThat(ignores.command()).containsExactly("C:\\Apps\\ide.exe");
        assertThat(ignores.folderOpened()).isFalse();
        assertThat(ignores.workingDirectory()).isEqualTo(project);
    }

    @Test
    void aFolderThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> LaunchPlan.of(desktop(null, "ide.exe", true), INSTALLED_EXE,
                root.resolve("missing"), List.of(), host))
                .isInstanceOf(SoftwareNotLaunchableException.class)
                .hasMessageContaining("does not exist");
    }

    // --- I3: no shell, no injected terminal syntax ------------------------------

    @Test
    void aCommandLineToolRunsInsideWindowsTerminalInTheProjectFolder() {
        Software claude = entry(LaunchKind.CLI, null, null, false, "claude", null, List.of(), null, true);

        LaunchPlan plan = LaunchPlan.of(claude, new Detection(Availability.INSTALLED, null, Path.of("claude.exe")),
                project, List.of("Esegui TASK-001 seguendo tasks/TASK-001.md"), host);

        assertThat(plan.command()).containsExactly(terminal.toString(), "-d", project.toString(), "claude",
                "Esegui TASK-001 seguendo tasks/TASK-001.md");
    }

    @Test
    void anArgumentThatWouldAddATerminalCommandIsRefused() {
        Software claude = entry(LaunchKind.CLI, null, null, false, "claude", null, List.of(), null, true);
        Detection installed = new Detection(Availability.INSTALLED, null, Path.of("claude.exe"));

        for (String hostile : List.of("x ; calc", "x\ncalc", "x\" & calc")) {
            assertThatThrownBy(() -> LaunchPlan.of(claude, installed, project, List.of(hostile), host))
                    .as(hostile)
                    .isInstanceOf(SoftwareNotLaunchableException.class);
        }
    }

    @Test
    void onlyCommandLineToolsTakeArguments() {
        assertThatThrownBy(() -> LaunchPlan.of(desktop(null, "ide.exe", true), INSTALLED_EXE, project,
                List.of("--anything"), host))
                .isInstanceOf(SoftwareNotLaunchableException.class);
    }

    @Test
    void withoutWindowsTerminalACommandLineToolIsNotLaunched() {
        HostEnvironment noTerminal = new HostEnvironment(host.variables(), true, null, root);
        Software claude = entry(LaunchKind.CLI, null, null, false, "claude", null, List.of(), null, true);

        assertThatThrownBy(() -> LaunchPlan.of(claude, new Detection(Availability.INSTALLED, null, null), null,
                List.of(), noTerminal))
                .isInstanceOf(SoftwareNotLaunchableException.class)
                .hasMessageContaining("wt.exe");
    }

    // --- I5 and the catalog's own refusals ---------------------------------------

    @Test
    void aWebEntryIsTheBrowsersJob() {
        Software web = entry(LaunchKind.WEB, null, null, false, null, "https://github.com", List.of(), null, true);

        assertThatThrownBy(() -> LaunchPlan.of(web, Detection.of(Availability.WEB, null), null, List.of(), host))
                .isInstanceOf(SoftwareNotLaunchableException.class)
                .hasMessageContaining("web site");
    }

    @Test
    void aDisabledOrIncompatibleEntryOrARunningServiceIsNotLaunched() {
        Software disabled = entry(LaunchKind.DESKTOP, "A.B", null, false, null, null, List.of(), null, false);
        Software incompatible = entry(LaunchKind.LOCAL_SERVICE, null, null, false, null, "http://127.0.0.1:8000",
                List.of(), "needs CUDA", true);
        Software running = entry(LaunchKind.LOCAL_SERVICE, "Open.WebUI", null, false, null, "http://localhost:8080",
                List.of(), null, true);

        assertThatThrownBy(() -> LaunchPlan.of(disabled, Detection.of(Availability.INSTALLED, null), null,
                List.of(), host)).hasMessageContaining("disabled");
        assertThatThrownBy(() -> LaunchPlan.of(incompatible,
                Detection.of(Availability.INCOMPATIBLE_HARDWARE, "needs CUDA"), null, List.of(), host))
                .hasMessageContaining("not compatible");
        assertThatThrownBy(() -> LaunchPlan.of(running, Detection.of(Availability.RUNNING, null), null,
                List.of(), host)).hasMessageContaining("already running");
    }
}
