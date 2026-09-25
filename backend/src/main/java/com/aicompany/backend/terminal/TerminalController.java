package com.aicompany.backend.terminal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Terminals are an admin's (SecurityConfiguration: /api/terminal/**), except the socket, which takes a ticket. */
@RestController
public class TerminalController {

    private final TerminalTickets tickets;

    public TerminalController(TerminalTickets tickets) {
        this.tickets = tickets;
    }

    public record TicketRequest(@NotBlank String shell, Long projectId) {
    }

    @GetMapping("/api/terminal/shells")
    public List<TerminalTickets.ShellView> shells() {
        return tickets.shells();
    }

    @PostMapping("/api/terminal/tickets")
    public ResponseEntity<TerminalTickets.Issued> ticket(@Valid @RequestBody TicketRequest request, Authentication caller) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(tickets.issue(request.shell(), request.projectId(), caller.getName()));
    }
}
