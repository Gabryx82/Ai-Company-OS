package com.aicompany.backend.software.controller;

import com.aicompany.backend.api.ETags;
import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.software.dto.LaunchRequest;
import com.aicompany.backend.software.dto.LaunchResponse;
import com.aicompany.backend.software.dto.SoftwareRequest;
import com.aicompany.backend.software.dto.SoftwareResponse;
import com.aicompany.backend.software.launch.LaunchPlan;
import com.aicompany.backend.software.service.SoftwareService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Duration;
import java.util.List;

/** The Software Hub (ADR-019). */
@RestController
@RequestMapping("/api/software")
public class SoftwareController {

    private final SoftwareService service;

    public SoftwareController(SoftwareService service) {
        this.service = service;
    }

    @GetMapping
    public List<SoftwareResponse> list() {
        return service.findAll().stream().map(d -> SoftwareResponse.from(d.software(), d.detection())).toList();
    }

    @GetMapping("/{key}")
    public ResponseEntity<SoftwareResponse> get(@PathVariable String key) {
        return ok(service.findByKey(key));
    }

    @PostMapping
    public ResponseEntity<SoftwareResponse> create(@Valid @RequestBody SoftwareRequest request) {
        SoftwareService.Detected created = service.create(request.key(), request.definition());
        return ResponseEntity.created(URI.create("/api/software/" + created.software().getKey()))
                .eTag(ETags.of(created.software().getVersion()))
                .body(SoftwareResponse.from(created.software(), created.detection()));
    }

    @PutMapping("/{key}")
    public ResponseEntity<SoftwareResponse> update(
            @PathVariable String key,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody SoftwareRequest request) {
        if (request.key() != null && !request.key().equals(key)) {
            throw new RequestValidationException("key", "a catalog key cannot be changed");
        }
        return ok(service.update(key, request.definition(), Precondition.fromHeader(ifMatch)));
    }

    /**
     * Opens the program on this machine. The body may name a project, whose
     * workspace becomes the program's folder; nothing else about the command
     * line comes from the caller (ADR-019 I1, I2).
     */
    @PostMapping("/{key}/launch")
    public ResponseEntity<LaunchResponse> launch(@PathVariable String key,
                                                 @RequestBody(required = false) LaunchRequest request) {
        LaunchPlan plan = service.launch(key, request == null ? null : request.projectId());
        return ResponseEntity.accepted().body(new LaunchResponse(key, plan.command(),
                plan.workingDirectory().toString(), plan.folderOpened()));
    }

    /** Forgets the cached detection, so the next read looks at the machine again. */
    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh() {
        service.refreshDetection();
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/{key}/icon", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> icon(@PathVariable String key) {
        return service.icon(key)
                .map(png -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
                        .contentType(MediaType.IMAGE_PNG)
                        .body(png))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static ResponseEntity<SoftwareResponse> ok(SoftwareService.Detected detected) {
        return ResponseEntity.ok()
                .eTag(ETags.of(detected.software().getVersion()))
                .body(SoftwareResponse.from(detected.software(), detected.detection()));
    }
}
