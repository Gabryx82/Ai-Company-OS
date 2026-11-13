package com.aicompany.backend.software.launch;

/**
 * Starts a {@link LaunchPlan} and does not wait for it: the program belongs to
 * the operator from then on, and outlives the control plane.
 */
public interface SoftwareLauncher {

    void start(LaunchPlan plan);

    /**
     * PHASE 36 (ADR-042): why this launcher would refuse every plan -- switched off, or not on
     * Windows -- or empty when it starts them. A handoff asks first, and prepares everything
     * without opening the tool instead of failing after the package is written.
     */
    default java.util.Optional<String> unavailable() {
        return java.util.Optional.empty();
    }
}
