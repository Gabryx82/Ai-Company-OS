package com.aicompany.backend.binding;

import com.aicompany.backend.binding.ExecutionTargetCatalog.Delivery;
import com.aicompany.backend.binding.ExecutionTargetCatalog.ExecutionTarget;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PHASE 34: the catalog's targets, read as the control plane reads them, and the rule
 * that ties a link to the delivery that uses it.
 */
class ExecutionTargetCatalogTest {

    private final ExecutionTargetCatalog catalog = new ExecutionTargetCatalog();

    @Test
    void claudeCodeIsATargetBothAsTheCliAndAsTheDesktopApp() {
        ExecutionTarget cli = catalog.find("claude-code").orElseThrow();
        ExecutionTarget app = catalog.find("claude-desktop").orElseThrow();

        assertThat(cli.delivery()).isEqualTo(Delivery.CLI_PROMPT);
        assertThat(cli.link()).isNull();
        assertThat(app.delivery()).isEqualTo(Delivery.APP_LINK);
        assertThat(app.link()).isEqualTo("claude://code/new");
        assertThat(app.software()).isEqualTo("claude-desktop");
        assertThat(app.providers()).containsExactly("claude-subscription");
        assertThat(app.contextFile()).isEqualTo("CLAUDE.md");
    }

    @Test
    void onlyAnAppLinkTargetHasALinkAndItAlwaysHasOneAndASoftware() {
        assertThatThrownBy(() -> new ExecutionTarget("x", "X", "x", Delivery.APP_LINK, List.of(), null, "how", null))
                .hasMessageContaining("needs one");
        assertThatThrownBy(() -> new ExecutionTarget("x", "X", "x", Delivery.CLI_PROMPT, List.of(), null, "how", "x://y"))
                .hasMessageContaining("belongs to an APP_LINK target");
        assertThatThrownBy(() -> new ExecutionTarget("x", "X", null, Delivery.APP_LINK, List.of(), null, "how", "x://y"))
                .hasMessageContaining("opens a software");
        assertThat(catalog.all()).filteredOn(t -> t.link() != null)
                .allMatch(t -> t.delivery() == Delivery.APP_LINK && t.software() != null);
    }

    /** PHASE 37 (ADR-043): OpenClaw is a CLI target whose agent works away from the folder, on OpenClaw's own model. */
    @Test
    void openClawIsATargetWhoseAgentWorksOutsideTheFolder() {
        ExecutionTarget openClaw = catalog.find(ExecutionTargetCatalog.OPENCLAW).orElseThrow();

        assertThat(openClaw.delivery()).isEqualTo(Delivery.CLI_PROMPT);
        assertThat(openClaw.software()).isEqualTo("openclaw");
        assertThat(openClaw.providers()).isEmpty();
        assertThat(openClaw.contextFile()).isEqualTo("AGENTS.md");
        assertThat(openClaw.worksOutsideTheFolder()).isTrue();
        assertThat(catalog.all()).filteredOn(ExecutionTarget::worksOutsideTheFolder).containsExactly(openClaw);
    }
}
