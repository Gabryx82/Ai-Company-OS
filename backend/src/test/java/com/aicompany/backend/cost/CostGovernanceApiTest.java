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

    /** The catalog ships no pay-per-token model since 2026-09-25 (operator: no APIs); the test brings its own. */
    private static final String PAID = "anthropic:cost-test-model";

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
        jdbc.update("""
                INSERT INTO llm_models (key, provider_key, display_name, role, lifecycle, created_at, updated_at)
                VALUES (?, 'anthropic', 'Cost test model', 'PREMIUM', 'ACTIVE', now(), now())
                ON CONFLICT (key) DO NOTHING""", PAID);
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

    /** PHASE 30 (ADR-035): removing a budget stops paid runs again; it never frees them. */
    @Test
    void aBudgetIsRemovedUnderItsTagAndPaidRunsWaitForANewOne() throws Exception {
        mockMvc.perform(put("/api/admin/costs/budgets/anthropic").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"monthlyLimitUsd\":5}"))
                .andExpect(status().isOk());
        long version = jdbc.queryForObject("SELECT version FROM cost_budgets WHERE provider_key = 'anthropic'", Long.class);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/costs/budgets/anthropic")
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\""))
                .andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/costs/budgets/anthropic")
                        .header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isPreconditionRequired());
        // A tag read before somebody changed the budget: the removal is refused, the budget stays.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/costs/budgets/anthropic")
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"" + (version + 5) + "\""))
                .andExpect(status().isPreconditionFailed());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_budgets", Integer.class)).isOne();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/costs/budgets/anthropic")
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"" + version + "\""))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cost_budgets", Integer.class)).isZero();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/admin/costs/budgets/anthropic")
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"" + version + "\""))
                .andExpect(status().isNotFound());

        run(task(), PAID).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:budget-required"));
    }

    /** PHASE 30 (ADR-035): a deleted agent's runs still count, under the name it had. */
    @Test
    void theSpendOfADeletedAgentStaysUnderItsName() throws Exception {
        long t = task();
        run(t, "echo:default").andExpect(status().isAccepted());
        awaitCost(t);
        // The run started the task; finished work does not hold the agent back.
        jdbc.update("UPDATE tasks SET status = 'DONE' WHERE id = ?", t);
        String name = jdbc.queryForObject("SELECT name FROM agents WHERE id = ?", String.class, agentId);
        String agentTag = mockMvc.perform(get("/api/agents/" + agentId)).andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/agents/" + agentId)
                        .header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, agentTag))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/costs"))
                .andExpect(jsonPath("$.agents[?(@.agentName == '" + name + "')].runs").value(1))
                .andExpect(jsonPath("$.agents[?(@.agentName == '" + name + "')].agentId").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        jdbc.update("DELETE FROM task_runs WHERE task_id = ?", t);
        jdbc.update("DELETE FROM tasks WHERE id = ?", t);
    }

    @Test
    void budgetsAndPricesAreAnAdminsDecision() throws Exception {
        mockMvc.perform(put("/api/admin/costs/budgets/anthropic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"monthlyLimitUsd\":100}"))
                .andExpect(status().isForbidden());
    }
}
