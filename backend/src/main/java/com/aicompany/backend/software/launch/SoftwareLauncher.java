package com.aicompany.backend.software.launch;

/**
 * Starts a {@link LaunchPlan} and does not wait for it: the program belongs to
 * the operator from then on, and outlives the control plane.
 */
public interface SoftwareLauncher {

    void start(LaunchPlan plan);
}
