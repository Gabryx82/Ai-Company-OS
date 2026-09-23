package com.aicompany.backend.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-17 and TD-18: the README must render, and must describe a stack that exists.
 *
 * <p>It said {@code \# AI Company OS} -- escaped markdown that renders as a
 * literal backslash -- and listed Vercel, LangGraph and Supabase, none of which
 * the repository has ever used. A README that describes another project is the
 * first false statement a newcomer reads. Like {@code DebtRegistryConsistencyTest}
 * this reads a file outside the module, and fails rather than skips when it cannot.
 */
class ReadmeTruthTest {

    @Test
    void theReadmeHasNoEscapedMarkdown() throws IOException {
        for (String line : readme()) {
            assertThat(line).as("TD-17: an escaped heading or list marker renders literally")
                    .doesNotStartWith("\\#").doesNotStartWith("\\-");
        }
    }

    @Test
    void theReadmeNamesOnlyWhatTheRepositoryContains() throws IOException {
        String text = String.join("\n", readme());
        assertThat(text).as("TD-18: technologies the repository does not use")
                .doesNotContain("Supabase").doesNotContain("Vercel").doesNotContain("LangGraph");

        Path root = repositoryRoot();
        for (String directory : List.of("backend", "ai-engine", "frontend", "docs/adr", "scripts")) {
            if (text.contains("`" + directory + "/`")) {
                assertThat(Files.isDirectory(root.resolve(directory)))
                        .as("the README points at %s/", directory).isTrue();
            }
        }
    }

    private static List<String> readme() throws IOException {
        return Files.readAllLines(repositoryRoot().resolve("README.md"), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".company-os"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("repository root not found");
    }
}
