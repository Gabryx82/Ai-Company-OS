package com.aicompany.backend.llm.service;

import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelLifecycle;
import com.aicompany.backend.llm.model.ModelRole;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PHASE 35 (ADR-041 §12): which model a task gets when nobody chose one -- short work to
 * the small model, planning and code to the 9B. The rule is the catalog's
 * ({@code catalog/model-routing.json}: word stems per role, the fallback roles, which role
 * prefers its lightest model), the models are the catalog's ({@code llm_models}: role,
 * also-serves, lifecycle, size). This class only applies them, the same way for the
 * Master Orchestrator's decision and for a run.
 */
@Component
public class ModelRouter {

    /** One rule of the catalog. {@code prefer} "smallest" picks the lightest model of the role. */
    public record Rule(ModelRole role, String label, List<String> stems, @Nullable String prefer) {
    }

    record Routing(ModelRole defaultRole, List<ModelRole> fallback, List<Rule> rules) {
    }

    /** The role a task needs, and why: the rule's label and the stems found; label null for the default. */
    public record Route(ModelRole role, @Nullable String label, List<String> matched) {

        /** In the operator's words, for a decision's reason. */
        public String explained() {
            return label == null ? "nessuna regola del catalogo riconosce la task"
                    : "regola del catalogo «" + label + "» (" + String.join(", ", matched) + ")";
        }
    }

    /** The model chosen, for which role, and whether that role is the model's own. */
    public record Pick(LlmModel model, ModelRole role, boolean own) {
    }

    private final Routing routing;
    private final List<Pattern> patterns = new ArrayList<>();

    public ModelRouter() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("catalog/model-routing.json")) {
            if (in == null) {
                throw new IllegalStateException("catalog/model-routing.json is missing");
            }
            this.routing = JsonMapper.builder().build().readValue(in, Routing.class);
        } catch (IOException e) {
            throw new UncheckedIOException("catalog/model-routing.json could not be read", e);
        }
        for (Rule rule : routing.rules()) {
            patterns.add(Pattern.compile("(?<!\\p{L})(" + String.join("|", rule.stems().stream().map(Pattern::quote).toList())
                    + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
        }
    }

    /** The role a task's text calls for: the rule with the most stems found, the earlier on a tie. */
    public Route route(String work) {
        String text = work == null ? "" : work.toLowerCase(Locale.ROOT);
        Route best = new Route(routing.defaultRole(), null, List.of());
        for (int i = 0; i < routing.rules().size(); i++) {
            LinkedHashSet<String> found = new LinkedHashSet<>();
            Matcher m = patterns.get(i).matcher(text);
            while (m.find()) {
                found.add(m.group(1).toLowerCase(Locale.ROOT));
            }
            if (found.size() > best.matched().size()) {
                Rule rule = routing.rules().get(i);
                best = new Route(rule.role(), rule.label(), List.copyOf(found));
            }
        }
        return best;
    }

    /**
     * The model for a role: among the catalog's ACTIVE models that serve it and that
     * {@code available} accepts, its own role first, then the lightest when the role's
     * rule prefers it, then by key; without one, the catalog's fallback roles in order.
     */
    public Optional<Pick> pick(ModelRole wanted, List<LlmModel> models, Predicate<String> available) {
        LinkedHashSet<ModelRole> roles = new LinkedHashSet<>();
        roles.add(wanted);
        roles.addAll(routing.fallback());
        for (ModelRole role : roles) {
            boolean smallest = routing.rules().stream().anyMatch(r -> r.role() == role && "smallest".equals(r.prefer()));
            Comparator<LlmModel> order = Comparator.comparing((LlmModel m) -> m.getRole() == role ? 0 : 1);
            if (smallest) {
                order = order.thenComparing(m -> m.getSizeGb() == null ? BigDecimal.valueOf(Long.MAX_VALUE) : m.getSizeGb());
            }
            Optional<LlmModel> found = models.stream()
                    .filter(m -> m.serves(role) && m.getLifecycle() == ModelLifecycle.ACTIVE && available.test(m.getKey()))
                    .min(order.thenComparing(LlmModel::getKey));
            if (found.isPresent()) {
                return Optional.of(new Pick(found.get(), role, found.get().getRole() == role));
            }
        }
        return Optional.empty();
    }
}
