package com.aicompany.backend.ecosystem;

import com.aicompany.backend.openclaw.OpenClawConsole;
import com.aicompany.backend.software.service.SoftwareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * ADR-046: when AI Company OS is ready, what its pages read first is looked at in the background -- the
 * catalog's detection (the Start menu through PowerShell, the local services' health) and OpenClaw's agents
 * and sessions (its commands take 12-60 s) -- so that the first page the operator opens has them instead of
 * waiting for them. Nothing is started or written: it only reads. {@code aicos.warm-up=false} turns it off
 * (the tests do).
 */
@Component
class EcosystemWarmUp {

    private static final Logger LOG = LoggerFactory.getLogger(EcosystemWarmUp.class);

    private final SoftwareService software;
    private final OpenClawConsole openClaw;
    private final boolean enabled;

    EcosystemWarmUp(SoftwareService software, OpenClawConsole openClaw, @Value("${aicos.warm-up:true}") boolean enabled) {
        this.software = software;
        this.openClaw = openClaw;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("aicos-warm-up").start(() -> {
            long start = System.nanoTime();
            try {
                software.findAll();
            } catch (RuntimeException e) {
                LOG.debug("Warm-up: the catalog was not detected: {}", e.getMessage());
            }
            try {
                // Starts OpenClaw's readings in the background when its gateway answers; returns at once.
                openClaw.overview(false);
            } catch (RuntimeException e) {
                LOG.debug("Warm-up: OpenClaw was not asked: {}", e.getMessage());
            }
            LOG.info("Warm-up: catalog detected in {} ms; OpenClaw's agents and sessions are being read",
                    (System.nanoTime() - start) / 1_000_000);
        });
    }
}
