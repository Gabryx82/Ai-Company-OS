package com.aicompany.backend.cost;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AdminSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 24 (ADR-031, TD-40): a pay-per-token run needs a budget and a price,
 * records what it cost, and stops when the month's budget is spent. Local runs
 * are unaffected.
 */
class CostGovernanceApiTest extends AbstractPostgresTest {

    private static final String PAID = "anthropic:claude-opus-5";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private String admin;
    private long agentId;

    @BeforeEach
    void anAgent() throws Exception {
        jdbc.update("DELETE FROM cost_budgets");
        jdbc.update("UPDATE llm_models SET input_price_per_mtok = NULL, output_price_per_mtok = NULL");
        admin = AdminSession.bearer(mockMvc);
        MvcResult created = mockMvc.perform(post("/api/agents").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Cost Bot " + System.nanoTime() + "\",\"role\":\"Analyst\",\"specialization\":\"costs\"}")).andReturn();
        agentId = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM cost_budgets");
        jdbc.update("UPDATE llm_models SET input_price_per_mtok = NULL, output_price_per_mtok = NULL");
    }

    private long task() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Stima\",\"status\":\"OPEN\",\"priority\":\"LOW\"}")).andReturn();
        long id = json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
        mockMvc.perform(put("/api/tasks/" + id + "/agent").header(HttpHeaders.IF_MATCH, created.getResponse().getHeader(HttpHeaders.ETAG))
                .contentType(MediaType.APPLICATION_JSON).content("{\"agentId\":" + agentId + "}")).andExpect(status().isOk());
        return id;
    }

    private String tag(long taskId) throws Exception {
        return mockMvc.perform(get("/api/tasks/" + taskId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
    }

    private org.springframework.test.web.servlet.ResultActions run(long taskId, String model) throws Exception {
        return mockMvc.perform(post("/api/tasks/" + taskId + "/runs").header(HttpHeaders.IF_MATCH, tag(taskId))
                .contentType(MediaType.APPLICATION_JSON).content(model == null ? "{}" : "{\"model\":\"" + model + "\"}"));
    }

    private BigDecimal awaitCost(long taskId) throws Exception {
        for (int i = 0; i < 200; i++) {
            var cost = jdbc.queryForList("SELECT cost_usd FROM task_runs WHERE task_id = ? AND status = 'SUCCEEDED'",
                    BigDecimal.class, taskId);
            if (!cost.isEmpty()) {
                return cost.getFirst();
            }
            Thread.sleep(25);
        }
        throw new AssertionError("the run did not finish");
    }

    @Test
    void aPaidRunNeedsABudgetAndAPriceThenRecordsItsCostAndStopsWhenTheMonthIsSpent() throws Exception {
        long first = task();
        run(first, PAID).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:budget-required"));

        mockMvc.perform(put("/api/admin/costs/budgets/anthropic").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimitUsd\":1.00,\"alertPercent\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monthlyLimitUsd").value(1.00));
        run(first, PAID).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:price-required"));

        long version = jdbc.queryForObject("SELECT version FROM llm_models WHERE key = ?", Long.class, PAID);
        mockMvc.perform(put("/api/admin/costs/prices/" + PAID).header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputPricePerMtok\":15,\"outputPricePerMtok\":75}"))
                .andExpect(status().isOk());

        run(first, PAID).andExpect(status().isAccepted());
        // The scripted engine reports 7 input and 7 output tokens: (7*15 + 7*75) / 1e6.
        assertThat(awaitCost(first)).isEqualByComparingTo("0.000630");

        mockMvc.perform(get("/api/costs"))
                .andExpect(jsonPath("$.providers[?(@.providerKey == 'anthropic')].costUsd").value(org.hamcrest.Matchers.hasItem(0.00063)))
                .andExpect(jsonPath("$.budgets[0].spentThisMonthUsd").value(0.00063));

        // A budget below what was spent: the next paid run is refused, a local one is not.
        long budgetVersion = jdbc.queryForObject("SELECT version FROM cost_budgets WHERE provider_key = 'anthropic'", Long.class);
        mockMvc.perform(put("/api/admin/costs/budgets/anthropic").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimitUsd\":0.0005}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/admin/costs/budgets/anthropic").header(HttpHeaders.AUTHORIZATION, admin)
                        .header(HttpHeaders.IF_MATCH, "\"" + budgetVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimitUsd\":0.0005}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exceeded").value(true));
        long second = task();
        run(second, PAID).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:budget-exceeded"));
        run(second, "echo:default").andExpect(status().isAccepted());
        assertThat(awaitCost(second)).isEqualByComparingTo("0");
    }

    @Test
    void budgetsAndPricesAreAnAdminsDecision() throws Exception {
        mockMvc.perform(put("/api/admin/costs/budgets/anthropic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyLimitUsd\":100}"))
                .andExpect(status().isForbidden());
    }
}
