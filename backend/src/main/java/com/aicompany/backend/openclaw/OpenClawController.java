package com.aicompany.backend.openclaw;

import com.aicompany.backend.openclaw.OpenClawConsole.OpenClawControlUi;
import com.aicompany.backend.openclaw.OpenClawConsole.OpenClawMessage;
import com.aicompany.backend.openclaw.OpenClawConsole.OpenClawOverview;
import com.aicompany.backend.openclaw.OpenClawConsole.OpenClawTerminal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ADR-045: OpenClaw inside the console. Reading is for whoever is signed in; everything
 * that acts -- a message to an agent, a terminal or the Control UI opened on this
 * machine -- is an administrator's (SecurityConfiguration).
 */
@RestController
public class OpenClawController {

    public record OpenClawMessageRequest(@NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9_-]{0,63}$") String agentId,
                                         @NotBlank @Size(max = 20_000) String message) {
    }

    /** A session key as OpenClaw writes them; nothing Windows Terminal would read as syntax. */
    public record OpenClawTerminalRequest(@NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9:._@+-]{0,199}$") String sessionKey) {
    }

    private final OpenClawConsole openClaw;

    public OpenClawController(OpenClawConsole openClaw) {
        this.openClaw = openClaw;
    }

    /** The gateway, the agents and the sessions, as last read; {@code refresh} starts a new reading. */
    @GetMapping("/api/openclaw")
    public OpenClawOverview openClawOverview(@RequestParam(defaultValue = "false") boolean refresh) {
        return openClaw.overview(refresh);
    }

    /** Opens the full Control UI in the operator's browser, paired by OpenClaw itself. */
    @PostMapping("/api/openclaw/control-ui")
    public ResponseEntity<OpenClawControlUi> openControlUi() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(openClaw.openControlUi());
    }

    /** Takes a session up again in {@code openclaw tui}, in Windows Terminal. */
    @PostMapping("/api/openclaw/terminal")
    public OpenClawTerminal openOpenClawTerminal(@Valid @RequestBody OpenClawTerminalRequest request) {
        return openClaw.openInTerminal(request.sessionKey());
    }

    /** Sends a message to an agent; the answer is read with {@code GET /api/openclaw/messages/{id}}. */
    @PostMapping("/api/openclaw/messages")
    public ResponseEntity<OpenClawMessage> sendOpenClawMessage(@Valid @RequestBody OpenClawMessageRequest request,
                                                               Authentication caller) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(openClaw.send(request.agentId(), request.message(), caller.getName()));
    }

    /** The messages sent from the console since the backend started, oldest first. */
    @GetMapping("/api/openclaw/messages")
    public List<OpenClawMessage> openClawMessages() {
        return openClaw.messages();
    }

    @GetMapping("/api/openclaw/messages/{id}")
    public OpenClawMessage openClawMessage(@PathVariable String id) {
        return openClaw.message(id);
    }
}
