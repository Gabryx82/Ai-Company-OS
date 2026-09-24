package com.aicompany.backend.software;

import com.aicompany.backend.software.detect.PathTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PathTemplateTest {

    @TempDir
    Path root;

    @Test
    void variablesAreExpandedCaseInsensitivelyLikeWindowsDoes() {
        assertThat(PathTemplate.expand("%localappdata%\\Programs\\x.exe", Map.of("LOCALAPPDATA", "C:\\L")))
                .contains("C:\\L\\Programs\\x.exe");
    }

    @Test
    void anUnsetVariableResolvesToNothingRatherThanToALiteralPercentPath() {
        assertThat(PathTemplate.expand("%NOPE%\\x.exe", Map.of())).isEmpty();
        assertThat(PathTemplate.resolveFile("%NOPE%\\x.exe", Map.of())).isEmpty();
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aWildcardSegmentFindsTheNewestVersionedFolder() throws IOException {
        for (String version : new String[]{"IDE 2025.3.4", "IDE 2026.2", "Other 2030"}) {
            Path bin = Files.createDirectories(root.resolve(version).resolve("bin"));
            Files.writeString(bin.resolve("ide64.exe"), version);
        }

        assertThat(PathTemplate.resolveFile("%ROOT%\\IDE *\\bin\\ide64.exe", Map.of("ROOT", root.toString())))
                .contains(root.resolve("IDE 2026.2").resolve("bin").resolve("ide64.exe"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void aTemplateWhoseFileIsMissingResolvesToNothing() {
        assertThat(PathTemplate.resolveFile("%ROOT%\\IDE *\\bin\\ide64.exe", Map.of("ROOT", root.toString())))
                .isEmpty();
    }
}
