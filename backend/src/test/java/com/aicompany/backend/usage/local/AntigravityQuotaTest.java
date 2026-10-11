package com.aicompany.backend.usage.local;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PHASE 33 (ADR-038): the model quotas Antigravity keeps on disk, and nothing else. */
class AntigravityQuotaTest {

    private static final Instant RESET = Instant.parse("2026-10-11T11:38:00Z");

    @Test
    void everyModelGivesItsUsedShareAndItsReset(@TempDir Path userData) throws Exception {
        String value = new AntigravityStateFixture()
                .model("Gemini 3.1 Pro (High)", 0.25f, RESET)
                .model("Claude Opus 4.6 (Thinking)", 1.0f, RESET.plusSeconds(3600))
                .model("GPT-OSS 120B (Medium)", null, RESET)
                .modelWithoutQuota("Experimental")
                .storedValue();
        AntigravityStateFixture.writeDatabase(userData, value);

        AntigravityQuota.Snapshot snapshot = new AntigravityQuota("antigravity", userData).latest().orElseThrow();

        assertThat(snapshot.models()).containsExactly(
                new AntigravityQuota.ModelQuota("Gemini 3.1 Pro (High)", 75.0, RESET),
                new AntigravityQuota.ModelQuota("Claude Opus 4.6 (Thinking)", 0.0, RESET.plusSeconds(3600)),
                // protobuf leaves a zero float out: nothing remains, all of it is used
                new AntigravityQuota.ModelQuota("GPT-OSS 120B (Medium)", 100.0, RESET));
        assertThat(snapshot.observedAt()).isNotNull();
    }

    @Test
    void theAccountsNameAndEmailNeverComeOut(@TempDir Path userData) throws Exception {
        AntigravityStateFixture.writeDatabase(userData,
                new AntigravityStateFixture().model("Gemini", 0.5f, RESET).storedValue());

        String everything = new AntigravityQuota("antigravity", userData).latest().orElseThrow().toString();

        assertThat(everything).contains("Gemini")
                .doesNotContain(AntigravityStateFixture.NAME)
                .doesNotContain(AntigravityStateFixture.EMAIL);
    }

    @Test
    void anUnrecognisedValueIsNoModelsNotAnError(@TempDir Path userData) throws Exception {
        assertThat(AntigravityQuota.models("not base64 at all!")).isEmpty();
        assertThat(AntigravityQuota.models("QUJDREVGR0hJSktMTU5PUA==")).isEmpty();

        AntigravityStateFixture.writeDatabase(userData, "CgQKAggB");
        assertThat(new AntigravityQuota("antigravity", userData).latest().orElseThrow().models()).isEqualTo(List.of());
    }

    @Test
    void noStateDatabaseIsNoDataNotAnError(@TempDir Path userData) {
        assertThat(new AntigravityQuota("antigravity", userData.resolve("missing")).latest()).isEmpty();
    }
}
