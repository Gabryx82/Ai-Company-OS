package com.aicompany.backend.docs;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TASK-011 -- the two debt numbering spaces stay interpretable.
 *
 * <p>{@code docs/audit/TECHNICAL_DEBT.md} (TASK-000) and the live numbering in
 * {@code PROJECT_STATE.md} use the same identifier space for different debts:
 * {@code TD-14}, {@code TD-19}, {@code TD-20}, {@code TD-21} and {@code TD-22}
 * mean one thing in the audit and another in the living register. The risk is
 * specific -- a later task can close the wrong debt believing it closed the right
 * one -- and what makes it insidious is that most identifiers behave: everything
 * from {@code TD-01} to {@code TD-13} coincides.
 *
 * <p>The fix is additive: neither space is renumbered, because every ADR, task
 * artifact and commit message already cites the live ids, and the audit is a
 * dated snapshot of what one task found. What is added is
 * {@code docs/DEBT_REGISTRY.md}, which says which space is authoritative and what
 * each colliding id means in both.
 *
 * <p><strong>Why a test for a documentation problem.</strong> The charter says a
 * debt is not closed because the code -- here, the prose -- looks different: it
 * needs a check that fails if the defect returns. The defect returning means an
 * audit identifier existing that the registry does not account for, and that is
 * exactly what this asserts.
 *
 * <h2>The path dependency, declared</h2>
 *
 * <p>This test reads files outside its own module, which nothing else in this
 * suite does. That coupling is deliberate and is the point -- the documents are
 * what is under test -- but it means the test can only be right about where the
 * repository root is. It resolves the root by walking up from the working
 * directory looking for {@code .company-os}, and it <strong>fails</strong> rather
 * than skips when it cannot find what it needs. A documentation guard that goes
 * green because it could not find the documentation is worse than no guard.
 */
class DebtRegistryConsistencyTest {

    private static final Pattern AUDIT_HEADING = Pattern.compile("(?m)^###\\s+(TD-\\d+)\\b");
    private static final Pattern REGISTRY_ID = Pattern.compile("\\bTD-\\d+\\b");

    /**
     * The five identifiers that mean different things in the two spaces, written
     * out because they are a finding and not a derivation. If a sixth ever
     * appears, somebody has to come here and say so.
     */
    private static final Set<String> KNOWN_COLLISIONS =
            Set.of("TD-14", "TD-19", "TD-20", "TD-21", "TD-22");

    @Test
    void everyAuditIdentifierIsAccountedForInTheRegistry() throws IOException {

        String audit = read("docs/audit/TECHNICAL_DEBT.md");
        String registry = read("docs/DEBT_REGISTRY.md");

        Set<String> auditIds = new LinkedHashSet<>();
        Matcher headings = AUDIT_HEADING.matcher(audit);
        while (headings.find()) {
            auditIds.add(headings.group(1));
        }

        // I-3. If the heading format ever changes, this test must fail loudly
        // rather than quietly checking an empty set against everything.
        assertThat(auditIds)
                .as("the audit register must still declare its debts as '### TD-NN' headings; "
                        + "an empty set here means this test stopped checking anything")
                .isNotEmpty();

        Set<String> registryIds = new LinkedHashSet<>();
        Matcher mentioned = REGISTRY_ID.matcher(registry);
        while (mentioned.find()) {
            registryIds.add(mentioned.group());
        }

        assertThat(registryIds)
                .as("every debt in the TASK-000 audit must appear in the registry, or somebody "
                        + "reading an id has no way to tell which space it belongs to")
                .containsAll(auditIds);
    }

    /**
     * I-2. The registry has to do the one thing it exists for: name the colliding
     * identifiers. A registry that listed the ids without flagging which of them
     * are ambiguous would be a table of contents.
     */
    @Test
    void theRegistryNamesEveryKnownCollision() throws IOException {

        String registry = read("docs/DEBT_REGISTRY.md");

        assertThat(registry).contains(KNOWN_COLLISIONS);
    }

    /**
     * Whoever opens the audit file has to learn, on the first screen, that its
     * identifiers are not the living ones. Putting that only in a separate
     * document would help exactly the people who already know.
     */
    @Test
    void theAuditFilePointsAtTheRegistryNearTheTop() throws IOException {

        String audit = read("docs/audit/TECHNICAL_DEBT.md");
        String head = audit.substring(0, Math.min(audit.length(), 1500));

        assertThat(head)
                .as("the pointer has to be where somebody lands, not at the bottom")
                .contains("DEBT_REGISTRY.md");
    }

    // --- helpers -----------------------------------------------------------

    private static String read(String relativePath) throws IOException {

        Path file = repositoryRoot().resolve(relativePath);

        assertThat(Files.exists(file))
                .as("expected to find %s -- this test reads documents outside the module and "
                        + "must fail, never pass, when it cannot", file)
                .isTrue();

        return Files.readString(file, StandardCharsets.UTF_8);
    }

    /**
     * Walks up from the working directory looking for {@code .company-os}, which
     * marks the repository root and is not present in any subdirectory.
     * Surefire's working directory is the module, so this normally climbs once.
     */
    private static Path repositoryRoot() {

        Path candidate = Path.of("").toAbsolutePath();

        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".company-os"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }

        throw new IllegalStateException(
                "repository root not found: no ancestor of " + Path.of("").toAbsolutePath()
                        + " contains a .company-os directory");
    }
}
