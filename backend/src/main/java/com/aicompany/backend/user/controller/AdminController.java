package com.aicompany.backend.user.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.security.Caller;
import com.aicompany.backend.user.controller.AuthController.UserResponse;
import com.aicompany.backend.user.model.AppUser;
import com.aicompany.backend.user.model.Role;
import com.aicompany.backend.user.model.SecurityEvent;
import com.aicompany.backend.user.service.AuthService;
import com.aicompany.backend.user.service.SecurityLog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/** People and the security log. Every route here is for admins only (SecurityConfiguration). */
@RestController
public class AdminController {

    private final AuthService auth;
    private final SecurityLog log;

    public AdminController(AuthService auth, SecurityLog log) {
        this.auth = auth;
        this.log = log;
    }

    public record NewUser(@NotBlank @Size(max = 64) String username, @Size(max = 120) String displayName,
                          @NotNull Role role, @NotBlank @Size(max = 200) String initialPassword) {
    }

    public record UserUpdate(@Size(max = 120) String displayName, @NotNull Role role, @NotNull Boolean enabled) {
    }

    public record PasswordReset(@NotBlank @Size(max = 200) String temporaryPassword) {
    }

    public record EventResponse(Long id, Instant occurredAt, String type, String principal, String source,
                                String detail) {
        static EventResponse from(SecurityEvent e) {
            return new EventResponse(e.getId(), e.getOccurredAt(), e.getType(), e.getPrincipal(), e.getSource(),
                    e.getDetail());
        }
    }

    @GetMapping("/api/admin/users")
    public List<UserResponse> users() {
        return auth.all().stream().map(UserResponse::from).toList();
    }

    @PostMapping("/api/admin/users")
    public ResponseEntity<UserResponse> create(@AuthenticationPrincipal Caller caller, @Valid @RequestBody NewUser r) {
        AppUser user = auth.create(caller, r.username(), r.displayName(), r.role(), r.initialPassword());
        return ResponseEntity.created(URI.create("/api/admin/users/" + user.getId()))
                .eTag(ETags.of(user.getVersion())).body(UserResponse.from(user));
    }

    @PutMapping("/api/admin/users/{id}")
    public ResponseEntity<UserResponse> update(@AuthenticationPrincipal Caller caller, @PathVariable Long id,
                                               @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                               @Valid @RequestBody UserUpdate r) {
        AppUser user = auth.update(caller, id, r.displayName(), r.role(), r.enabled(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(user.getVersion())).body(UserResponse.from(user));
    }

    @PutMapping("/api/admin/users/{id}/password")
    public ResponseEntity<UserResponse> resetPassword(@AuthenticationPrincipal Caller caller, @PathVariable Long id,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                      @Valid @RequestBody PasswordReset r) {
        AppUser user = auth.resetPassword(caller, id, r.temporaryPassword(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(user.getVersion())).body(UserResponse.from(user));
    }

    @GetMapping("/api/admin/security-events")
    public List<EventResponse> events(@RequestParam(defaultValue = "100") int limit) {
        return log.latest(limit).stream().map(EventResponse::from).toList();
    }
}
