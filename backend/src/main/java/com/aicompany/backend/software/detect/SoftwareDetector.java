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
}
