package com.aicompany.backend.terminal;

import com.aicompany.backend.api.RequestValidationException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Terminals are an admin's (SecurityConfiguration: /api/terminal/**), except the socket, which takes a ticket. */
@RestController
public class TerminalController {

    private final TerminalTickets tickets;
    private final TerminalSocketHandler terminals;

    public TerminalController(TerminalTickets tickets, TerminalSocketHandler terminals) {
        this.tickets = tickets;
        this.terminals = terminals;
    }

    /** A new shell ({@code shell}, optionally in a project), or one of the caller's running shells shown again ({@code attach}). */
    public record TicketRequest(String shell, Long projectId, String attach) {
    }

    @GetMapping("/api/terminal/shells")
    public List<TerminalTickets.ShellView> shells() {
        return tickets.shells();
    }

    @PostMapping("/api/terminal/tickets")
    public ResponseEntity<TerminalTickets.Issued> ticket(@RequestBody TicketRequest request, Authentication caller) {
        TerminalTickets.Issued issued;
        if (request.attach() != null && !request.attach().isBlank()) {
            TerminalSocketHandler.TerminalSession shell = terminals.find(request.attach(), caller.getName())
                    .orElseThrow(() -> new TerminalNotFoundException(request.attach()));
            issued = tickets.issueAttach(shell, caller.getName());
        } else if (request.shell() == null || request.shell().isBlank()) {
            throw new RequestValidationException("shell", "is required, unless a running terminal is attached");
        } else {
            issued = tickets.issue(request.shell(), request.projectId(), caller.getName());
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(issued);
    }

    /** The caller's running shells: shown on a socket, or detached and waiting to be attached again (ADR-029 §5). */
    @GetMapping("/api/terminal/sessions")
    public List<TerminalSocketHandler.TerminalSession> terminalSessions(Authentication caller) {
        return terminals.list(caller.getName());
    }

    @DeleteMapping("/api/terminal/sessions/{id}")
    public ResponseEntity<Void> closeTerminalSession(@PathVariable String id, Authentication caller) {
        if (!terminals.close(id, caller.getName())) {
            throw new TerminalNotFoundException(id);
        }
        return ResponseEntity.noContent().build();
    }
}
