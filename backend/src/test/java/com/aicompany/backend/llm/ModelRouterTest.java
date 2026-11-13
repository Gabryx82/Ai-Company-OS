package com.aicompany.backend.llm;

import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelRole;
import com.aicompany.backend.llm.service.ModelRouter;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 35 (ADR-041 §12): the catalog's route, as {@code catalog/model-routing.json}
 * writes it -- which role a task's words call for, and which model then serves it.
 */
class ModelRouterTest {

    private final ModelRouter router = new ModelRouter();

    private static LlmModel model(String key, ModelRole role, String size, ModelRole... alsoServes) {
        LlmModel m = new LlmModel(key, key.substring(0, key.indexOf(':')), key, role, List.of(), null,
                new BigDecimal(size), null, ModelLifecycle.ACTIVE, null, null);
        m.alsoServe(List.of(alsoServes));
        return m;
    }

    @Test
    void theWordsOfATaskCallForARoleAndTheHeavierRoleWinsATie() {
        assertThat(router.route("Riassumi il verbale della riunione").role()).isEqualTo(ModelRole.FAST);
        assertThat(router.route("Classifica le segnalazioni per gravità").role()).as("not «class»").isEqualTo(ModelRole.FAST);
        assertThat(router.route("Pianifica la roadmap del trimestre").role()).isEqualTo(ModelRole.PLANNER);
        assertThat(router.route("Implementa l'endpoint dei contatti").role()).isEqualTo(ModelRole.CODER);
        assertThat(router.route("Scrivi il controller REST").role()).as("a controller is code, not a check")
                .isEqualTo(ModelRole.CODER);
        assertThat(router.route("Verifica i test dell'API").role()).isEqualTo(ModelRole.CODER);
        ModelRouter.Route none = router.route("Scrivi un messaggio di benvenuto");
        assertThat(none.role()).isEqualTo(ModelRole.GENERAL);
        assertThat(none.label()).isNull();
        assertThat(router.route("Riassumi il verbale").explained()).contains("compiti brevi").contains("riassum");
    }

    @Test
    void theSmallestServesShortWorkAndTheFallbackServesWhatNothingElseDoes() {
        LlmModel qwen9 = model("ollama:qwen3.5:9b", ModelRole.PLANNER, "6.6", ModelRole.CODER);
        LlmModel lfm = model("ollama:lfm2.5:8b", ModelRole.FAST, "5.2");
        LlmModel qwen4 = model("ollama:qwen3.5:4b", ModelRole.FAST, "3.4");
        List<LlmModel> catalog = List.of(qwen9, lfm, qwen4);
        Set<String> all = Set.of(qwen9.getKey(), lfm.getKey(), qwen4.getKey());

        assertThat(router.pick(ModelRole.FAST, catalog, all::contains).orElseThrow().model()).isEqualTo(qwen4);
        ModelRouter.Pick coder = router.pick(ModelRole.CODER, catalog, all::contains).orElseThrow();
        assertThat(coder.model()).isEqualTo(qwen9);
        assertThat(coder.own()).isFalse();
        // GENERAL has no model of its own: the catalog's fallback, PLANNER first.
        assertThat(router.pick(ModelRole.GENERAL, catalog, all::contains).orElseThrow().role()).isEqualTo(ModelRole.PLANNER);
        // The small one is not served: the next FAST model, not the 9B.
        assertThat(router.pick(ModelRole.FAST, catalog, key -> !key.equals(qwen4.getKey())).orElseThrow().model()).isEqualTo(lfm);
        assertThat(router.pick(ModelRole.FAST, catalog, key -> false)).isEmpty();
    }
}
