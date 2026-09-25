package com.aicompany.backend.ecosystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * When AI Company OS is ready, the ecosystem starts too (ADR-028) -- in the
 * background, so the control plane answers at once and a slow or broken
 * secondary tool never delays or stops it. {@code aicos.ecosystem.autostart=false}
 * turns this off (the tests do).
 */
@Component
class EcosystemStartup {

    private static final Logger LOG = LoggerFactory.getLogger(EcosystemStartup.class);

    private final EcosystemService ecosystem;
    private final boolean enabled;

    EcosystemStartup(EcosystemService ecosystem, @Value("${aicos.ecosystem.autostart:true}") boolean enabled) {
        this.ecosystem = ecosystem;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        ecosystem.ensureDefaults();
        if (!enabled) {
            return;
        }
        Thread.ofVirtual().name("ecosystem-startup").start(() -> {
            try {
                ecosystem.startAll("startup").forEach(s -> LOG.info("Ecosystem {}: {} -- {}", s.key(), s.lastStatus(),
                        s.lastMessage() == null ? "" : s.lastMessage()));
            } catch (RuntimeException e) {
                LOG.warn("The ecosystem start did not complete: {}", e.getMessage());
            }
        });
    }
}
