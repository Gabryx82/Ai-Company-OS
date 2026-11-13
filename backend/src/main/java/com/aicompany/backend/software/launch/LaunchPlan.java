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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
 *
 * <p>PHASE 33 (ADR-040) widens I2 by one argument, and only for {@link #forFile}: a
 * file of that same folder, which the caller resolved inside it ({@code WorkspacePaths})
 * and which goes where the catalog's {@code {file}} placeholder says -- as a whole
 * argument, absolute, to an {@code .exe} only.
 *
 * @param file the file opened, or {@code null} for a launch that opens none
 */
public record LaunchPlan(List<String> command, Path workingDirectory, boolean folderOpened, Path file) {

    /** The placeholders of {@code file_args}: whole arguments, never part of one. */
    public static final String FILE = "{file}";
    public static final String FOLDER = "{folder}";

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
            return new LaunchPlan(List.copyOf(command), workingDirectory, folder != null, null);
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
            return new LaunchPlan(List.copyOf(command), workingDirectory, opensFolder, null);
        }
        if (software.getAppId() != null && detection.availability() != Availability.NOT_INSTALLED) {
            String explorer = PathTemplate.expand("%SystemRoot%\\explorer.exe", environment)
                    .orElse("explorer.exe");
            return new LaunchPlan(List.of(explorer, "shell:AppsFolder\\" + software.getAppId()),
                    workingDirectory, false, null);
        }
        throw new SoftwareNotLaunchableException("'" + software.getKey() + "' is not installed on this machine");
    }

    /**
     * PHASE 33 (ADR-040): the command line that opens one file of a project with a
     * catalog entry. The program is the catalog's executable (I1); the arguments are
     * the catalog's, with {@code {file}} and {@code {folder}} replaced by the two
     * paths the caller resolved -- the file must be a regular file inside the folder.
     */
    public static LaunchPlan forFile(Software software, Detection detection, Path folder, Path file,
                                     HostEnvironment host) {
        String key = software.getKey();
        if (!software.isEnabled()) {
            throw new SoftwareNotLaunchableException("'" + key + "' is disabled in the catalog");
        }
        if (detection.availability() == Availability.INCOMPATIBLE_HARDWARE) {
            throw new SoftwareNotLaunchableException(
                    "'" + key + "' is not compatible with this machine: " + detection.detail());
        }
        if (software.getFileArgs().isEmpty()) {
            throw new SoftwareNotLaunchableException(
                    "'" + key + "' does not open files: its catalog entry has no file arguments");
        }
        fileArgumentsProblem(software.getLaunchKind(), software.getExecutable(), software.getFileArgs())
                .ifPresent(problem -> {
                    throw new SoftwareNotLaunchableException("'" + key + "' cannot open files: " + problem);
                });
        if (detection.availability() != Availability.INSTALLED || detection.executable() == null) {
            throw new SoftwareNotLaunchableException("'" + key + "' is not installed with an executable a file can be "
                    + "passed to" + (detection.detail() == null ? "" : ": " + detection.detail()));
        }
        if (!passesAFile(detection.executable())) {
            throw new SoftwareNotLaunchableException("'" + key + "' runs " + detection.executable().getFileName()
                    + ": only an .exe receives a file -- a .cmd or .bat would go through cmd.exe, which reads & and % "
                    + "in a file name as commands");
        }
        if (folder == null || !Files.isDirectory(folder)) {
            throw new SoftwareNotLaunchableException("The project folder " + folder + " does not exist");
        }
        Path root = folder.toAbsolutePath().normalize();
        Path target = file.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root) || !Files.isRegularFile(target)) {
            throw new SoftwareNotLaunchableException("'" + file + "' is not a file of the project folder");
        }

        List<String> command = new ArrayList<>();
        command.add(detection.executable().toString());
        // The catalog's own arguments first, as a plain launch has them.
        software.getExecutableArgs().forEach(argument ->
                command.add(PathTemplate.expand(argument, host.variables()).orElse(argument)));
        for (String argument : software.getFileArgs()) {
            command.add(switch (argument) {
                case FILE -> target.toString();
                case FOLDER -> root.toString();
                default -> argument;
            });
        }
        return new LaunchPlan(List.copyOf(command), root, software.getFileArgs().contains(FOLDER), target);
    }

    /** What Claude Desktop keeps of a prompt in a link (support article 14729294): about 14,000 characters. */
    public static final int MAX_LINK_PROMPT = 14_000;

    /** A custom scheme and a route, nothing else: the query is the handoff's, never the catalog's. */
    private static final Pattern APP_LINK = Pattern.compile("^[a-z][a-z0-9+.-]{1,31}://[A-Za-z0-9._/-]+$");

    /** Schemes Windows would hand to something other than the app the catalog names. */
    private static final java.util.Set<String> NOT_AN_APP_SCHEME = java.util.Set.of(
            "http", "https", "file", "ftp", "ms-settings", "shell", "search-ms", "javascript", "data");

    /**
     * PHASE 34: the command line that opens a desktop app through its own link -- Claude
     * Desktop's {@code claude://code/new} -- on a project folder, with a prompt in its
     * composer. The program is {@code explorer.exe}, which hands the link to the handler
     * Windows registered for the scheme. The invariants, as for a CLI:
     *
     * <ul>
     *   <li><b>I1</b> the scheme and the route are the target catalog's, and must be an
     *       app's own scheme -- never the web's (I5), a file's or the shell's;</li>
     *   <li><b>I2</b> the folder is the caller's, an existing directory;</li>
     *   <li><b>I3</b> no shell: the link is one argument, and its two values are
     *       percent-encoded, so it holds nothing but letters, digits, {@code %._*-} and the
     *       {@code ?=&} that join them -- no space, quote or {@code ;} for anyone to parse.</li>
     * </ul>
     */
    public static LaunchPlan forLink(Software software, Detection detection, String link, Path folder, String prompt,
                                     HostEnvironment host) {
        String key = software.getKey();
        if (!software.isEnabled()) {
            throw new SoftwareNotLaunchableException("'" + key + "' is disabled in the catalog");
        }
        if (software.getLaunchKind() != LaunchKind.DESKTOP) {
            throw new SoftwareNotLaunchableException("'" + key + "' is not a desktop app: only an app opens through its link");
        }
        if (detection.availability() != Availability.INSTALLED && detection.availability() != Availability.RUNNING) {
            throw new SoftwareNotLaunchableException("'" + key + "' is not installed on this machine"
                    + (detection.detail() == null ? "" : ": " + detection.detail()));
        }
        if (link == null || !APP_LINK.matcher(link).matches()
                || NOT_AN_APP_SCHEME.contains(link.substring(0, link.indexOf(':')))) {
            throw new SoftwareNotLaunchableException("'" + link + "' is not an app link the catalog can open");
        }
        if (folder == null || !Files.isDirectory(folder)) {
            throw new SoftwareNotLaunchableException("The project folder " + folder + " does not exist");
        }
        if (prompt == null || prompt.isBlank() || prompt.length() > MAX_LINK_PROMPT) {
            throw new SoftwareNotLaunchableException("The prompt of a link must have between 1 and "
                    + MAX_LINK_PROMPT + " characters");
        }
        Path root = folder.toAbsolutePath().normalize();
        String url = link + "?folder=" + encode(root.toString()) + "&q=" + encode(prompt);
        String explorer = PathTemplate.expand("%SystemRoot%\\explorer.exe", host.variables()).orElse("explorer.exe");
        return new LaunchPlan(List.of(explorer, url), root, true, null);
    }

    /** RFC 3986 percent-encoding: a space is {@code %20}, not the form encoding's {@code +}. */
    static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** Whether {@link #forFile} can run for this entry as detected, whatever the file. */
    public static boolean takesAFile(Software software, Detection detection) {
        return software.getLaunchKind() == LaunchKind.DESKTOP
                && !software.getFileArgs().isEmpty()
                && detection.executable() != null
                && passesAFile(detection.executable());
    }

    /**
     * The rule of {@code software_file_args_check} (V41), and a little more: a desktop
     * entry with an executable; {@code {file}} exactly once and {@code {folder}} at most
     * once, each a whole argument; no other braces, so that no argument is half a path.
     */
    public static Optional<String> fileArgumentsProblem(LaunchKind kind, String executable, List<String> fileArgs) {
        if (fileArgs == null || fileArgs.isEmpty()) {
            return Optional.empty();
        }
        if (kind != LaunchKind.DESKTOP || executable == null || executable.isBlank()) {
            return Optional.of("only a desktop entry with an executable opens files");
        }
        int files = 0;
        int folders = 0;
        for (String argument : fileArgs) {
            if (argument == null || argument.isBlank()) {
                return Optional.of("an argument is empty");
            }
            if (argument.equals(FILE)) {
                files++;
            } else if (argument.equals(FOLDER)) {
                folders++;
            } else if (argument.contains("{") || argument.contains("}")) {
                return Optional.of("'" + argument + "': {file} and {folder} are whole arguments, and there is no other placeholder");
            }
        }
        if (files != 1) {
            return Optional.of("{file} must appear exactly once");
        }
        if (folders > 1) {
            return Optional.of("{folder} may appear at most once");
        }
        return Optional.empty();
    }

    /** A file goes to an executable Windows starts directly, never to a script cmd.exe would parse. */
    static boolean passesAFile(Path executable) {
        return executable.getFileName() != null
                && executable.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe");
    }
}
