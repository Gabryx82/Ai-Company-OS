package com.aicompany.backend.ecosystem;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The ecosystem's services and their start (ADR-028). Reading and starting are
 * the operator's; choosing what starts with AI Company OS is the admin's, under
 * {@code /api/admin/**}.
 */
@RestController
public class EcosystemController {

    private final EcosystemService ecosystem;

    public EcosystemController(EcosystemService ecosystem) {
        this.ecosystem = ecosystem;
    }

    public record Autostart(@NotNull Boolean autostart, @NotNull Integer position, @NotNull Integer timeoutSeconds) {
    }

    public record NewService(@NotBlank String key) {
    }

    @GetMapping("/api/ecosystem/services")
    public List<EcosystemService.ServiceView> services() {
        return ecosystem.status();
    }

    @PostMapping("/api/ecosystem/services/{key}/start")
    public EcosystemService.ServiceView start(@PathVariable String key, Authentication caller) {
        return ecosystem.start(key, caller.getName());
    }

    @PostMapping("/api/ecosystem/start-all")
    public List<EcosystemService.ServiceView> startAll(Authentication caller) {
        return ecosystem.startAll(caller.getName());
    }

    @PutMapping("/api/admin/ecosystem/services/{key}")
    public ResponseEntity<EcosystemService.ServiceView> configure(@PathVariable String key,
                                                                  @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                                  @Valid @RequestBody Autostart body) {
        EcosystemService.ServiceView view = ecosystem.configure(key, body.autostart(), body.position(),
                body.timeoutSeconds(), Precondition.fromHeader(ifMatch));
        return ResponseEntity.ok().eTag(ETags.of(view.version())).body(view);
    }

    @PostMapping("/api/admin/ecosystem/services")
    public EcosystemService.ServiceView add(@Valid @RequestBody NewService body) {
        return ecosystem.add(body.key());
    }
}
