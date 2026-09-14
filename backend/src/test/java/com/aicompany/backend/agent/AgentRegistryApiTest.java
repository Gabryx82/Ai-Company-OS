package com.aicompany.backend.agent;

import com.aicompany.backend.agent.model.Agent;
import com.aicompany.backend.agent.repository.AgentRepository;
import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the agent registry.
 *
 * <p>Deliberately a mirror of the project registry's test: the two answer the
 * same questions, and reading them side by side is how a divergence gets
 * noticed. The one that exists on purpose -- lifecycle in a boolean here, in an
 * enum there -- is TD-31, and {@link #statusIsDerivedAndNotStored()} is what
 * keeps it from reaching the client.
 */
class AgentRegistryApiTest extends AbstractPostgresTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AgentRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clearAgents() {
        repository.deleteAll();
    }

    // --- creation ----------------------------------------------------------

    @Test
    void anAgentIsCreatedActiveAndAddressable() throws Exception {

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Code Architect","role":"Engineer","specialization":"architecture"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("Code Architect"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void anEmptyPayloadIsRejectedWithTheFieldsThatFailed() throws Exception {

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:validation-failed"))
                .andExpect(jsonPath("$.errors.name").exists())
                .andExpect(jsonPath("$.errors.role").exists())
                .andExpect(jsonPath("$.errors.specialization").exists());
    }

    /** ADR-004 §5: a registry holding both spellings of one name is a broken registry. */
    @Test
    void aNameThatDiffersOnlyByCaseIsAConflict() throws Exception {

        activeAgent("Code Architect");

        mockMvc.perform(post("/api/agents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"code architect","role":"Engineer","specialization":"x"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-name-conflict"));
    }

    // --- reading -----------------------------------------------------------

    @Test
    void theListingFiltersByLifecycleAndIncludesEverythingWithoutIt() throws Exception {

        Long active = activeAgent("Code Architect");
        Long inactive = activeAgent("Retired Specialist");
        mockMvc.perform(post("/api/agents/" + inactive + "/deactivate")).andExpect(status().isOk());

        mockMvc.perform(get("/api/agents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(2)));

        mockMvc.perform(get("/api/agents?active=true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(active));

        mockMvc.perform(get("/api/agents?active=false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(inactive))
                .andExpect(jsonPath("$[0].status").value("INACTIVE"));
    }

    @Test
    void anUnknownAgentIsNotFound() throws Exception {

        mockMvc.perform(get("/api/agents/424242"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:agent-not-found"));
    }

    // --- lifecycle ---------------------------------------------------------

    /**
     * ADR-004 §8 applied to agents, and the three-request flow it implies:
     * activate, edit, deactivate. Accepted there for the same reason -- it makes
     * explicit that something put away is being taken out again.
     */
    @Test
    void anInactiveAgentIsImmutableUntilItIsActivated() throws Exception {

        Long id = activeAgent("Code Architect");
        mockMvc.perform(post("/api/agents/" + id + "/deactivate")).andExpect(status().isOk());

        String body = """
                {"name":"Renamed","role":"Engineer","specialization":"architecture"}""";

        mockMvc.perform(put("/api/agents/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:ai-company-os:problem:inactive-agent-is-immutable"));

        assertThat(nameOf(id)).isEqualTo("Code Architect");

        mockMvc.perform(post("/api/agents/" + id + "/activate")).andExpect(status().isOk());
        mockMvc.perform(put("/api/agents/" + id).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));
    }

    @Test
    void aRepeatedTransitionIsAConflict() throws Exception {

        Long id = activeAgent("Code Architect");

        mockMvc.perform(post("/api/agents/" + id + "/deactivate")).andExpect(status().isOk());
        mockMvc.perform(post("/api/agents/" + id + "/deactivate"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:ai-company-os:problem:illegal-agent-state-transition"));

        mockMvc.perform(post("/api/agents/" + id + "/activate")).andExpect(status().isOk());
        mockMvc.perform(post("/api/agents/" + id + "/activate"))
                .andExpect(status().isConflict());
    }

    /** ADR-004 §3: agents are deactivated, not deleted. 405 says "not by this route". */
    @Test
    void thereIsNoWayToDeleteAnAgent() throws Exception {

        Long id = activeAgent("Code Architect");

        mockMvc.perform(delete("/api/agents/" + id))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:method-not-allowed"));

        assertThat(repository.findById(id)).isPresent();
    }

    // --- the derived status, which is the whole point of TD-31 -------------

    /**
     * AC-9, invariant I-7. {@code status} exists in the response and nowhere in
     * the database.
     *
     * <p>That is what lets the client see one vocabulary across the API while the
     * database keeps one representation of the state. Storing it as well would be
     * the same state twice, which is the alternative ADR-004 rejected for
     * {@code deleted_at} and ADR-006 rejected for the archival cascade.
     */
    @Test
    void statusIsDerivedAndNotStored() throws Exception {

        Long id = activeAgent("Code Architect");

        assertThat(columnsOfAgents())
                .as("status must not become a column: one state, one place")
                .doesNotContain("status")
                .contains("active", "created_at", "updated_at");

        mockMvc.perform(get("/api/agents/" + id))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(post("/api/agents/" + id + "/deactivate"))
                .andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // --- helpers -----------------------------------------------------------

    private Long activeAgent(String name) {
        return repository.saveAndFlush(new Agent(name, "Engineer", "architecture")).getId();
    }

    private String nameOf(Long id) {
        return jdbc.queryForObject("SELECT name FROM agents WHERE id = ?", String.class, id);
    }

    private java.util.List<String> columnsOfAgents() {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_name = 'agents' ORDER BY column_name", String.class);
    }
}
