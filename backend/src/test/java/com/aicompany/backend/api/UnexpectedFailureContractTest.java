package com.aicompany.backend.api;

import com.aicompany.backend.project.service.ProjectService;
import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AC-9, invariant I-5. The failure nobody anticipated is still inside the
 * contract, and says nothing about the inside of the system.
 *
 * <p>Two things are being asserted at once, and they pull in opposite
 * directions. A response that falls outside the contract is worst exactly when
 * the client understands least -- so an unexpected failure has to have the same
 * shape as everything else. But the message of an internal exception carries
 * table names, SQL fragments and file paths, so returning it opens a channel
 * nobody decided to open. Fixed detail, full stack in the log (ADR-007 §4).
 *
 * <p>The service is replaced with a double rather than provoking a real failure:
 * a genuine one would be a failure of something specific, and this needs to stand
 * for anything at all.
 */
class UnexpectedFailureContractTest extends AbstractPostgresTest {

    private static final String SECRET = "relation \"projects\" does not exist at /var/secret/db.sql:42";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProjectService projectService;

    @Test
    void anUnexpectedFailureKeepsTheContractAndLeaksNothing() throws Exception {

        when(projectService.findAll(any())).thenThrow(new IllegalStateException(SECRET));

        MvcResult result = mockMvc.perform(get("/api/projects"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:internal-error"))
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.title").value("Internal error"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed"))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertThat(body)
                .as("""
                    nothing of the original failure may reach the caller: not the message, \
                    not the exception type, not a stack frame. The detail is fixed and the \
                    exception is logged in full, which is where it stays useful.""")
                .doesNotContain(SECRET)
                .doesNotContain("IllegalStateException")
                .doesNotContain("projects\" does not exist")
                .doesNotContain("/var/secret")
                .doesNotContain("com.aicompany");
    }
}
