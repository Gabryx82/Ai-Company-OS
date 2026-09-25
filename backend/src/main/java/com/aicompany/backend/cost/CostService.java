package com.aicompany.backend.cost;

import com.aicompany.backend.api.Precondition;
import com.aicompany.backend.api.PreconditionRequiredException;
import com.aicompany.backend.api.RequestValidationException;
import com.aicompany.backend.llm.exception.ModelNotFoundException;
import com.aicompany.backend.llm.exception.ProviderNotFoundException;
import com.aicompany.backend.llm.model.Billing;
import com.aicompany.backend.llm.model.LlmModel;
import com.aicompany.backend.llm.model.ModelProvider;
import com.aicompany.backend.llm.repository.LlmModelRepository;
import com.aicompany.backend.llm.repository.ModelProviderRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Cost governance (ADR-031, closes TD-40). Three rules:
 *
 * <ol>
 *   <li>every finished run records what it cost, at the price of that moment --
 *       zero for local and subscription models, unknown when a paid model has no
 *       price;</li>
 *   <li>a run on a <strong>pay-per-token</strong> provider needs a monthly budget
 *       for that provider and a price for that model, or it is refused before
 *       anything is sent;</li>
 *   <li>once the month's spend reaches the budget, the provider's runs are
 *       refused until the next month or a higher budget.</li>
 * </ol>
 */
@Service
@Transactional
public class CostService {

    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);

    public record ProviderSpend(String providerKey, String providerName, String billing, int runs, long inputTokens,
                                long outputTokens, BigDecimal costUsd, int runsWithoutPrice) {
    }

    public record ModelSpend(String model, String providerKey, int runs, long inputTokens, long outputTokens,
                             BigDecimal costUsd, BigDecimal inputPricePerMtok, BigDecimal outputPricePerMtok) {
    }

    public record AgentSpend(Long agentId, String agentName, int runs, long tokens, BigDecimal costUsd) {
    }

    public record Budget(String providerKey, String providerName, BigDecimal monthlyLimitUsd, int alertPercent,
                         BigDecimal spentThisMonthUsd, int percent, boolean alert, boolean exceeded, long version) {
    }

    public record Summary(LocalDate from, LocalDate to, List<ProviderSpend> providers, List<ModelSpend> models,
                          List<AgentSpend> agents, List<Budget> budgets, BigDecimal totalUsd) {
    }

    private final LlmModelRepository models;
    private final ModelProviderRepository providers;
    private final JdbcTemplate jdbc;

    public CostService(LlmModelRepository models, ModelProviderRepository providers, JdbcTemplate jdbc) {
        this.models = models;
        this.providers = providers;
        this.jdbc = jdbc;
    }

    // --- per run -----------------------------------------------------------------------

    /** What a finished run cost; empty when it is a paid model without a price. */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> costOf(String modelKey, int inputTokens, int outputTokens) {
        if (modelKey == null) {
            return Optional.of(BigDecimal.ZERO);
        }
        Optional<ModelProvider> provider = providerOf(modelKey);
        if (provider.isEmpty() || provider.get().getBilling() != Billing.PAY_PER_TOKEN) {
            return Optional.of(BigDecimal.ZERO);
        }
        Optional<LlmModel> model = models.findByKey(modelKey);
        if (model.isEmpty() || model.get().getInputPricePerMtok() == null || model.get().getOutputPricePerMtok() == null) {
            return Optional.empty();
        }
        BigDecimal cost = model.get().getInputPricePerMtok().multiply(BigDecimal.valueOf(inputTokens))
                .add(model.get().getOutputPricePerMtok().multiply(BigDecimal.valueOf(outputTokens)))
                .divide(MILLION, 6, RoundingMode.HALF_UP);
        return Optional.of(cost);
    }

    /** Refuses a run that would spend money without a budget, a price, or room left in the month. */
    @Transactional(readOnly = true)
    public void requireBudget(String modelKey) {
        if (modelKey == null) {
            return;
        }
        Optional<ModelProvider> provider = providerOf(modelKey);
        if (provider.isEmpty() || provider.get().getBilling() != Billing.PAY_PER_TOKEN) {
            return;
        }
        String key = provider.get().getKey();
        List<Map<String, Object>> budget = jdbc.queryForList("SELECT monthly_limit_usd FROM cost_budgets WHERE provider_key = ?", key);
        if (budget.isEmpty()) {
            throw CostProblemException.budgetRequired(provider.get().getName());
        }
        Optional<LlmModel> model = models.findByKey(modelKey);
        if (model.isEmpty() || model.get().getInputPricePerMtok() == null || model.get().getOutputPricePerMtok() == null) {
            throw CostProblemException.priceRequired(modelKey);
        }
        BigDecimal limit = (BigDecimal) budget.getFirst().get("monthly_limit_usd");
        BigDecimal spent = spentThisMonth(key);
        if (spent.compareTo(limit) >= 0) {
            throw CostProblemException.budgetExceeded(provider.get().getName(), spent, limit);
        }
    }

    // --- summaries ---------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Summary summary(LocalDate from, LocalDate to) {
        Timestamp start = Timestamp.from(from.atStartOfDay(ZoneId.systemDefault()).toInstant());
        Timestamp end = Timestamp.from(to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
        String base = "FROM task_runs r LEFT JOIN llm_models m ON m.key = COALESCE(r.requested_model, r.served_model) "
                + "LEFT JOIN model_providers p ON p.key = COALESCE(m.provider_key, split_part(COALESCE(r.requested_model, r.served_model), ':', 1)) "
                + "WHERE r.status = 'SUCCEEDED' AND r.finished_at >= ? AND r.finished_at < ? ";
        List<ProviderSpend> byProvider = jdbc.query("SELECT COALESCE(p.key, 'engine-default') AS key, COALESCE(p.name, 'Default dell''engine') AS name, "
                        + "COALESCE(p.billing, 'FREE') AS billing, count(*) AS runs, COALESCE(sum(r.input_tokens),0) AS input, "
                        + "COALESCE(sum(r.output_tokens),0) AS output, COALESCE(sum(r.cost_usd),0) AS cost, "
                        + "count(*) FILTER (WHERE r.cost_usd IS NULL AND p.billing = 'PAY_PER_TOKEN') AS unpriced "
                        + base + "GROUP BY 1,2,3 ORDER BY cost DESC, runs DESC",
                (rs, n) -> new ProviderSpend(rs.getString("key"), rs.getString("name"), rs.getString("billing"), rs.getInt("runs"),
                        rs.getLong("input"), rs.getLong("output"), rs.getBigDecimal("cost"), rs.getInt("unpriced")), start, end);
        List<ModelSpend> byModel = jdbc.query("SELECT COALESCE(r.requested_model, r.served_model, 'default') AS model, "
                        + "COALESCE(p.key, '') AS provider, count(*) AS runs, COALESCE(sum(r.input_tokens),0) AS input, "
                        + "COALESCE(sum(r.output_tokens),0) AS output, COALESCE(sum(r.cost_usd),0) AS cost, "
                        + "max(m.input_price_per_mtok) AS pin, max(m.output_price_per_mtok) AS pout "
                        + base + "GROUP BY 1,2 ORDER BY cost DESC, runs DESC",
                (rs, n) -> new ModelSpend(rs.getString("model"), rs.getString("provider"), rs.getInt("runs"), rs.getLong("input"),
                        rs.getLong("output"), rs.getBigDecimal("cost"), rs.getBigDecimal("pin"), rs.getBigDecimal("pout")), start, end);
        List<AgentSpend> byAgent = jdbc.query("SELECT a.id, a.name, count(*) AS runs, "
                        + "COALESCE(sum(r.input_tokens),0) + COALESCE(sum(r.output_tokens),0) AS tokens, COALESCE(sum(r.cost_usd),0) AS cost "
                        + base.replace("FROM task_runs r ", "FROM task_runs r JOIN agents a ON a.id = r.agent_id ")
                        + "GROUP BY a.id, a.name ORDER BY cost DESC, tokens DESC",
                (rs, n) -> new AgentSpend(rs.getLong("id"), rs.getString("name"), rs.getInt("runs"), rs.getLong("tokens"),
                        rs.getBigDecimal("cost")), start, end);
        BigDecimal total = byProvider.stream().map(ProviderSpend::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Summary(from, to, byProvider, byModel, byAgent, budgets(), total);
    }

    @Transactional(readOnly = true)
    public List<Budget> budgets() {
        List<Budget> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList("SELECT b.*, p.name FROM cost_budgets b "
                + "LEFT JOIN model_providers p ON p.key = b.provider_key ORDER BY b.provider_key")) {
            String key = (String) row.get("provider_key");
            BigDecimal limit = (BigDecimal) row.get("monthly_limit_usd");
            int alert = ((Number) row.get("alert_percent")).intValue();
            BigDecimal spent = spentThisMonth(key);
            int percent = limit.signum() == 0 ? (spent.signum() > 0 ? 100 : 0)
                    : spent.multiply(BigDecimal.valueOf(100)).divide(limit, 0, RoundingMode.HALF_UP).intValue();
            out.add(new Budget(key, (String) row.get("name"), limit, alert, spent, percent, percent >= alert,
                    spent.compareTo(limit) >= 0, ((Number) row.get("version")).longValue()));
        }
        return out;
    }

    // --- admin -------------------------------------------------------------------------

    /** Creates a budget, or replaces one under its tag (ADR-009). */
    public Budget setBudget(String providerKey, BigDecimal limit, int alertPercent, Optional<Precondition> precondition) {
        providers.findByKey(providerKey).orElseThrow(() -> new ProviderNotFoundException(providerKey));
        if (limit == null || limit.signum() < 0) {
            throw new RequestValidationException("monthlyLimitUsd", "must be zero or more");
        }
        if (alertPercent < 1 || alertPercent > 100) {
            throw new RequestValidationException("alertPercent", "must be between 1 and 100");
        }
        List<Map<String, Object>> existing = jdbc.queryForList(
                "SELECT version FROM cost_budgets WHERE provider_key = ? FOR UPDATE", providerKey);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO cost_budgets (provider_key, monthly_limit_usd, alert_percent) VALUES (?, ?, ?)",
                    providerKey, limit, alertPercent);
        } else {
            precondition.orElseThrow(PreconditionRequiredException::new)
                    .requireSatisfiedBy(((Number) existing.getFirst().get("version")).longValue());
            jdbc.update("UPDATE cost_budgets SET monthly_limit_usd = ?, alert_percent = ?, version = version + 1, "
                    + "updated_at = now() WHERE provider_key = ?", limit, alertPercent, providerKey);
        }
        return budgets().stream().filter(b -> b.providerKey().equals(providerKey)).findFirst().orElseThrow();
    }

    /** The price of a model, under its tag. */
    public LlmModel setPrice(String modelKey, BigDecimal input, BigDecimal output, Precondition precondition) {
        LlmModel model = models.findByKeyForUpdate(modelKey).orElseThrow(() -> new ModelNotFoundException(modelKey));
        precondition.requireSatisfiedBy(model.getVersion());
        if ((input != null && input.signum() < 0) || (output != null && output.signum() < 0)) {
            throw new RequestValidationException("price", "must be zero or more");
        }
        model.price(input, output);
        models.flush();
        return model;
    }

    // --- helpers -----------------------------------------------------------------------

    private BigDecimal spentThisMonth(String providerKey) {
        Instant monthStart = ZonedDateTime.now().withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant();
        BigDecimal spent = jdbc.queryForObject("SELECT COALESCE(sum(r.cost_usd), 0) FROM task_runs r "
                + "LEFT JOIN llm_models m ON m.key = COALESCE(r.requested_model, r.served_model) "
                + "WHERE r.status = 'SUCCEEDED' AND r.finished_at >= ? "
                + "AND COALESCE(m.provider_key, split_part(COALESCE(r.requested_model, r.served_model), ':', 1)) = ?",
                BigDecimal.class, Timestamp.from(monthStart), providerKey);
        return spent == null ? BigDecimal.ZERO : spent;
    }

    private Optional<ModelProvider> providerOf(String modelKey) {
        String providerKey = models.findByKey(modelKey).map(LlmModel::getProviderKey)
                .orElse(modelKey.contains(":") ? modelKey.substring(0, modelKey.indexOf(':')) : modelKey);
        return providers.findByKey(providerKey);
    }
}
