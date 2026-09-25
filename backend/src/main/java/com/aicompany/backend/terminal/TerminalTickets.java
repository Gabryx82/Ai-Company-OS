package com.aicompany.backend.terminal;

import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.software.detect.Availability;
import com.aicompany.backend.software.service.HostEnvironment;
import com.aicompany.backend.software.service.ProjectFolders;
import com.aicompany.backend.software.service.SoftwareService;
import com.aicompany.backend.software.exception.SoftwareNotLaunchableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may open a terminal, with which shell, where (ADR-029). A terminal is a
 * process on the operator's machine, so it is opened in two steps: an admin asks
 * for a ticket over the authenticated API, then presents it -- once, within 30
 * seconds -- as the first message of the WebSocket. The command and the folder
 * are resolved when the ticket is issued, from the catalog and the database;
 * nothing the socket sends can change them.
 */
@Component
public class TerminalTickets {

    /** The shells the terminal offers: catalog entries, resolved by detection. */
    public static final Map<String, String> SHELLS = Map.of(
            "powershell", "PowerShell",
            "claude-code", "Claude Code",
            "opencode", "OpenCode");

    static final Duration TTL = Duration.ofSeconds(30);

    public record Ticket(String digest, String shell, List<String> command, Path directory, String issuedTo,
                         Instant expiresAt) {
    }

    public record Issued(String ticket, String shell, String directory, List<String> command, Instant expiresAt) {
    }

    public record ShellView(String key, String name, boolean available, String detail) {
    }

    private final SoftwareService software;
    private final ProjectFolders folders;
    private final HostEnvironment host;
    private final List<String> testCommand;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    public TerminalTickets(SoftwareService software, ProjectFolders folders, HostEnvironment host,
                           @Value("${aicos.terminal.test-command:}") List<String> testCommand) {
        this.software = software;
        this.folders = folders;
        this.host = host;
        this.testCommand = testCommand == null ? List.of() : testCommand.stream().filter(s -> !s.isBlank()).toList();
    }

    public List<ShellView> shells() {
        List<ShellView> out = new ArrayList<>();
        for (String key : List.of("powershell", "claude-code", "opencode")) {
            try {
                SoftwareService.Detected d = software.findByKey(key);
                boolean available = d.detection().executable() != null
                        && d.detection().availability() != Availability.NOT_INSTALLED;
                out.add(new ShellView(key, SHELLS.get(key), available,
                        available ? d.detection().executable().toString() : d.detection().detail()));
            } catch (RuntimeException e) {
                out.add(new ShellView(key, SHELLS.get(key), false, e.getMessage()));
            }
        }
        if (!testCommand.isEmpty()) {
            out.add(new ShellView("test", "Test", true, String.join(" ", testCommand)));
        }
        return out;
    }

    public Issued issue(String shell, Long projectId, String issuedTo) {
        List<String> command = command(shell);
        Path directory = projectId == null ? host.home() : folders.folderOf(projectId)
                .orElseThrow(() -> new SoftwareNotLaunchableException("Project " + projectId + " has no workspace folder yet"));
        purgeExpired();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expires = Instant.now().plus(TTL);
        tickets.put(digest(token), new Ticket(digest(token), shell, command, directory, issuedTo, expires));
        return new Issued(token, shell, directory.toString(), command, expires);
    }

    /** The ticket, removed: a second presentation of the same token finds nothing. */
    public Optional<Ticket> consume(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Ticket ticket = tickets.remove(digest(token));
        if (ticket == null || ticket.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(ticket);
    }

    private List<String> command(String shell) {
        if ("test".equals(shell) && !testCommand.isEmpty()) {
            return testCommand;
        }
        if (!SHELLS.containsKey(shell)) {
            throw new RequestValidationException("shell", "must be one of " + String.join(", ", SHELLS.keySet()));
        }
        SoftwareService.Detected d = software.findByKey(shell);
        if (d.detection().executable() == null || d.detection().availability() == Availability.NOT_INSTALLED) {
            throw new SoftwareNotLaunchableException(SHELLS.get(shell) + " is not installed on this machine");
        }
        return List.of(d.detection().executable().toString());
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tickets.values().removeIf(t -> t.expiresAt().isBefore(now));
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
