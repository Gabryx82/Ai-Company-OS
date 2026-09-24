package com.aicompany.backend.software.detect;

import java.nio.file.Path;

/**
 * The detected state of one entry, with the executable it resolved to when it
 * resolved to one -- the launcher opens exactly that file, never a path from a
 * request (ADR-019 I1).
 */
public record Detection(Availability availability, String detail, Path executable) {

    public static Detection of(Availability availability, String detail) {
        return new Detection(availability, detail, null);
    }

    public boolean usable() {
        return availability == Availability.INSTALLED
                || availability == Availability.RUNNING
                || availability == Availability.STOPPED
                || availability == Availability.WEB;
    }
}
