package com.aicompany.backend.software.detect;

/** What detection says about a catalog entry on this machine (ADR-019 §3). Never stored. */
public enum Availability {
    INSTALLED,
    NOT_INSTALLED,
    /** A web site: always reachable from the console, nothing to install. */
    WEB,
    /** A local service answering on its health URL. */
    RUNNING,
    /** A local service installed or configured, and not answering now. */
    STOPPED,
    /** A known hardware mismatch, stated in the catalog rather than detected. */
    INCOMPATIBLE_HARDWARE,
    /** Detection is not possible where the control plane runs (not Windows). */
    UNKNOWN
}
