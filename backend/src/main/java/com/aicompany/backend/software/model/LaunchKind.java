package com.aicompany.backend.software.model;

/**
 * How a catalog entry is opened (ADR-019 §2). Mirrored by
 * {@code software_launch_kind_check} (V12).
 */
public enum LaunchKind {
    /** A desktop application: Start-menu AppID, or an executable. */
    DESKTOP,
    /** A command-line tool, run inside Windows Terminal. */
    CLI,
    /** A web site: the console opens it, the control plane never does (ADR-019 I5). */
    WEB,
    /** A service on this machine with a web UI (Open WebUI, 3D Omniverse). */
    LOCAL_SERVICE
}
