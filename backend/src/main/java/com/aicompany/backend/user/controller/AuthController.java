package com.aicompany.backend.user.controller;

import com.aicompany.backend.security.Caller;
import com.aicompany.backend.user.model.AppUser;
import com.aicompany.backend.user.model.Role;
import com.aicompany.backend.user.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Sign-in, sign-out, who am I, and one's own password (ADR-024). */
@RestController
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username,
                               @NotBlank @Size(max = 200) String password) {
    }

    public record UserResponse(Long id, String username, String displayName, Role role, boolean enabled,
                               boolean mustChangePassword, Instant lastLoginAt, Instant passwordChangedAt,
                               long version) {
        public static UserResponse from(AppUser user) {
            return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole(),
                    user.isEnabled(), user.isMustChangePassword(), user.getLastLoginAt(),
                    user.getPasswordChangedAt(), user.getVersion());
        }
    }

    public record LoginResponse(String token, Instant expiresAt, UserResponse user) {
    }

    public record MeResponse(String name, Role role, Caller.Kind kind, UserResponse user) {
    }

    public record PasswordChange(@NotBlank @Size(max = 200) String currentPassword,
                                 @NotBlank @Size(max = 200) String newPassword) {
    }

    /** Public: the one write that needs no credential, because it is how one gets one. */
    @PostMapping("/api/auth/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        AuthService.SignedIn signedIn = auth.login(request.username(), request.password(), http.getRemoteAddr(),
                http.getHeader(HttpHeaders.USER_AGENT));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new LoginResponse(signedIn.token(), signedIn.expiresAt(), UserResponse.from(signedIn.user())));
    }

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Caller caller, HttpServletRequest http) {
        auth.logout(caller, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/auth/me")
    public MeResponse me(@AuthenticationPrincipal Caller caller) {
        UserResponse user = caller.kind() == Caller.Kind.USER ? UserResponse.from(auth.current(caller)) : null;
        return new MeResponse(caller.name(), caller.role(), caller.kind(), user);
    }

    @PutMapping("/api/auth/password")
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal Caller caller,
                                               @Valid @RequestBody PasswordChange request, HttpServletRequest http) {
        auth.changePassword(caller, request.currentPassword(), request.newPassword(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }
}
