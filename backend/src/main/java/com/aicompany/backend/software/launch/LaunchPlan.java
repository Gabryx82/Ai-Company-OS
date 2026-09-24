package com.aicompany.backend.software.launch;

import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.detect.Detection;
import com.aicompany.backend.software.detect.PathTemplate;
import com.aicompany.backend.software.exception.SoftwareNotLaunchableException;
import com.aicompany.backend.software.model.LaunchKind;
import com.aicompany.backend.software.model.Software;
import com.aicompany.backend.software.service.HostEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The exact command line that opens a catalog entry -- a pure function, and the
 * place where ADR-019's invariants are enforced:
 *
 * <ul>
 *   <li><b>I1</b> the program is the catalog's: the resolved executable, the
 *       Start-menu AppID through {@code explorer.exe}, or the CLI command inside
 *       Windows Terminal. Nothing in the request names a program;</li>
 *   <li><b>I2</b> the only variable argument is a folder the caller resolved from
 *       the database, and it must be an existing directory;</li>
 *   <li><b>I3</b> no shell: a list of arguments, never one string for
 *       {@code cmd /c}; CLI arguments are refused if they contain the characters
 *       Windows Terminal would treat as syntax;</li>
 *   <li><b>I5</b> web entries are the browser's job.</li>
 * </ul>
 */
public record LaunchPlan(List<String> command, Path workingDirectory, boolean folderOpened) {

    /** Windows Terminal reads {@code ;} as "new tab" -- an argument must not be able to add a command. */
    private static final Pattern UNSAFE_CLI_ARGUMENT = Pattern.compile("[;\\r\\n\\u0000\"]");

    public static LaunchPlan of(Software software, Detection detection, Path folder,
                                List<String> cliArguments, HostEnvironment host) {

        Map<String, String> environment = host.variables();

        if (!software.isEnabled()) {
            throw new SoftwareNotLaunchableException("'" + software.getKey() + "' is disabled in the catalog");
        }
        if (detection.availability() == Availability.INCOMPATIBLE_HARDWARE) {
            throw new SoftwareNotLaunchableException(
                    "'" + software.getKey() + "' is not compatible with this machine: " + detection.detail());
        }
        if (software.getLaunchKind() == LaunchKind.WEB) {
            throw new SoftwareNotLaunchableException(
                    "'" + software.getKey() + "' is a web site: the console opens " + software.getUrl());
        }
        if (software.getLaunchKind() == LaunchKind.LOCAL_SERVICE && detection.availability() == Availability.RUNNING) {
            throw new SoftwareNotLaunchableException(
                    "'" + software.getKey() + "' is already running at " + software.getUrl());
        }
        if (folder != null && !Files.isDirectory(folder)) {
            throw new SoftwareNotLaunchableException("The project folder " + folder + " does not exist");
        }
        if (!cliArguments.isEmpty() && software.getLaunchKind() != LaunchKind.CLI) {
            throw new SoftwareNotLaunchableException("Only command-line tools take arguments");
        }
        for (String argument : cliArguments) {
            if (UNSAFE_CLI_ARGUMENT.matcher(argument).find()) {
                throw new SoftwareNotLaunchableException(
                        "A command-line argument contains a character Windows Terminal would interpret");
            }
        }

        Path workingDirectory = folder != null ? folder : host.home();

        if (software.getLaunchKind() == LaunchKind.CLI) {
            String terminal = host.terminal().map(Path::toString)
                    .orElseThrow(() -> new SoftwareNotLaunchableException(
                            "Windows Terminal (wt.exe) is not installed; command-line tools open inside it"));
            if (detection.availability() == Availability.NOT_INSTALLED) {
                throw new SoftwareNotLaunchableException(
                        "'" + software.getCliCommand() + "' is not installed: " + detection.detail());
            }
            List<String> command = new ArrayList<>(List.of(terminal, "-d", workingDirectory.toString()));
            command.addAll(List.of(software.getCliCommand().strip().split("\\s+")));
            command.addAll(cliArguments);
            return new LaunchPlan(List.copyOf(command), workingDirectory, folder != null);
        }

        // DESKTOP, or a LOCAL_SERVICE that is not running.
        if (detection.executable() != null) {
            List<String> command = new ArrayList<>();
            command.add(detection.executable().toString());
            // Catalog arguments may carry %ENV% placeholders too; they are still the catalog's.
            software.getExecutableArgs().forEach(argument ->
                    command.add(PathTemplate.expand(argument, environment).orElse(argument)));
            boolean opensFolder = folder != null && software.isOpenFolder();
            if (opensFolder) {
                command.add(folder.toString());
            }
            return new LaunchPlan(List.copyOf(command), workingDirectory, opensFolder);
        }
        if (software.getAppId() != null && detection.availability() != Availability.NOT_INSTALLED) {
            String explorer = PathTemplate.expand("%SystemRoot%\\explorer.exe", environment)
                    .orElse("explorer.exe");
            return new LaunchPlan(List.of(explorer, "shell:AppsFolder\\" + software.getAppId()),
                    workingDirectory, false);
        }
        throw new SoftwareNotLaunchableException("'" + software.getKey() + "' is not installed on this machine");
    }
}
