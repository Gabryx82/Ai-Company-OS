package com.aicompany.backend.software.detect;

import com.aicompany.backend.software.model.Software;

import java.util.Optional;

/**
 * The real icon of an installed program, as PNG bytes -- read from the program
 * itself, so the Software Hub shows what the operator sees in the Start menu
 * without the repository shipping anybody's logo.
 */
public interface SoftwareIcons {

    Optional<byte[]> icon(Software software, Detection detection);
}
