package com.aicompany.backend.software.detect;

import com.aicompany.backend.software.model.Software;

/**
 * Detects catalog entries on the machine the control plane runs on. The real
 * implementation asks Windows; tests replace it, and no test runs PowerShell.
 */
public interface SoftwareDetector {

    Detection detect(Software software);

    /** Forgets cached observations, so the next {@link #detect} looks again. */
    void refresh();

    /**
     * ADR-046: forgets only what the local services answered, so the next detection asks them again --
     * what waits for a service to come up needs nothing else looked at again.
     */
    default void forgetServices() {
        refresh();
    }

    /**
     * ADR-046: looks at many entries at once, before they are detected one by one -- what can be asked
     * together is asked together, so a catalog takes the time of its slowest entry and not their sum.
     */
    default void prepare(java.util.Collection<Software> all) {
    }
}
