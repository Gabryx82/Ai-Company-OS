package com.aicompany.backend.support;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * An admin session for tests of admin-only routes (ADR-024). The suite's default
 * client is an OPERATOR service token; a test that needs an admin signs in as the
 * one created at startup from application-test.properties.
 */
public final class AdminSession {

    public static final String PASSWORD = "test-root-secret-0123";

    private AdminSession() {
    }

    /** The value of an Authorization header for a fresh admin session. */
    public static String bearer(MockMvc mockMvc) throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}")).andReturn();
        if (login.getResponse().getStatus() != 200) {
            throw new IllegalStateException("The test admin could not sign in: " + login.getResponse().getContentAsString());
        }
        return "Bearer " + JsonMapper.builder().build().readTree(login.getResponse().getContentAsString())
                .path("token").asString();
    }
}
