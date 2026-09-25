package com.aicompany.backend.security;

import com.aicompany.backend.support.AbstractPostgresTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 15 (ADR-024): people sign in, sessions end, roles decide, and the
 * security log records it. The admin is the one created at startup from
 * {@code aicos.security.admin.password} in application-test.properties.
 */
class AuthApiTest extends AbstractPostgresTest {

    static final String ADMIN_PASSWORD = "test-root-secret-0123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void onlyTheAdminRemains() {
        jdbc.update("DELETE FROM auth_sessions");
        jdbc.update("DELETE FROM app_users WHERE username <> 'admin'");
        jdbc.update("UPDATE app_users SET failed_attempts = 0, locked_until = NULL, enabled = TRUE, role = 'ADMIN'");
        jdbc.update("DELETE FROM security_events");
    }

    private ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new Credentials(username, password))));
    }

    private record Credentials(String username, String password) {
    }

    private String tokenOf(String username, String password) throws Exception {
        MvcResult result = login(username, password).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString()).path("token").asString();
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private long createUser(String adminToken, String username, String role, String password) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"role\":\"" + role + "\",\"initialPassword\":\"" + password + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return json.readTree(created.getResponse().getContentAsString()).path("id").asLong();
    }

    @Test
    void theAdminCreatedAtStartupSignsInAndGetsASessionThatIdentifiesThem() throws Exception {
        MvcResult result = login("Admin ", ADMIN_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.user.username").value("admin"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"))
                .andReturn();
        JsonNode body = json.readTree(result.getResponse().getContentAsString());
        String token = body.path("token").asString();
        assertThat(token).startsWith("aicos_s_").hasSizeGreaterThan(40);

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("admin"))
                .andExpect(jsonPath("$.kind").value("USER"))
                .andExpect(jsonPath("$.role").value("ADMIN"));

        // Neither the password nor the token is stored in clear.
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE username = 'admin'", String.class);
        assertThat(hash).startsWith("$2a$12$").doesNotContain(ADMIN_PASSWORD);
        List<String> digests = jdbc.queryForList("SELECT token_digest FROM auth_sessions", String.class);
        assertThat(digests).hasSize(1).first().asString().hasSize(64).isNotEqualTo(token);
    }

    @Test
    void aWrongPasswordAndAnUnknownUserGetTheSameAnswer() throws Exception {
        String wrong = login("admin", "not-the-password-at-all").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:invalid-credentials"))
                .andReturn().getResponse().getContentAsString();
        String unknown = login("nobody-here", "not-the-password-at-all").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(unknown).path("detail")).isEqualTo(json.readTree(wrong).path("detail"));
        assertThat(jdbc.queryForList("SELECT type FROM security_events ORDER BY id", String.class))
                .containsExactly("LOGIN_FAILED", "LOGIN_FAILED");
    }

    @Test
    void fiveWrongPasswordsLockTheAccountEvenAgainstTheRightOne() throws Exception {
        String admin = tokenOf("admin", ADMIN_PASSWORD);
        createUser(admin, "mario", "OPERATOR", "correct-horse-battery");
        for (int i = 0; i < 5; i++) {
            login("mario", "wrong-password-" + i).andExpect(status().isUnauthorized());
        }
        login("mario", "correct-horse-battery").andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT locked_until IS NOT NULL FROM app_users WHERE username = 'mario'",
                Boolean.class)).isTrue();
        assertThat(jdbc.queryForList("SELECT type FROM security_events WHERE principal = 'mario'", String.class))
                .contains("ACCOUNT_LOCKED");
    }

    @Test
    void signingOutEndsTheSessionAndNothingElse() throws Exception {
        String first = tokenOf("admin", ADMIN_PASSWORD);
        String second = tokenOf("admin", ADMIN_PASSWORD);

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, bearer(first)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(first)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(second)))
                .andExpect(status().isOk());
    }

    @Test
    void changingOnesPasswordNeedsTheCurrentOneAndEndsEveryOtherSession() throws Exception {
        String admin = tokenOf("admin", ADMIN_PASSWORD);
        createUser(admin, "lucia", "OPERATOR", "first-secret-of-l-u");
        String laptop = tokenOf("lucia", "first-secret-of-l-u");
        String phone = tokenOf("lucia", "first-secret-of-l-u");

        mockMvc.perform(put("/api/auth/password").header(HttpHeaders.AUTHORIZATION, bearer(laptop))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"guessing-wrongly\",\"newPassword\":\"a-much-better-secret\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:current-password-wrong"));
        mockMvc.perform(put("/api/auth/password").header(HttpHeaders.AUTHORIZATION, bearer(laptop))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"first-secret-of-l-u\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:weak-password"));

        mockMvc.perform(put("/api/auth/password").header(HttpHeaders.AUTHORIZATION, bearer(laptop))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"first-secret-of-l-u\",\"newPassword\":\"a-much-better-secret\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(laptop))).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(phone))).andExpect(status().isUnauthorized());
        login("lucia", "first-secret-of-l-u").andExpect(status().isUnauthorized());
        login("lucia", "a-much-better-secret").andExpect(status().isOk());
    }

    @Test
    void aPersonRenamesThemselvesAndTheNameComesBackFromMe() throws Exception {
        String admin = tokenOf("admin", ADMIN_PASSWORD);
        createUser(admin, "marta", "OPERATOR", "first-secret-of-m-a");
        String marta = tokenOf("marta", "first-secret-of-m-a");

        mockMvc.perform(put("/api/auth/profile").header(HttpHeaders.AUTHORIZATION, bearer(marta))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"  Marta Rossi \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Marta Rossi"))
                .andExpect(jsonPath("$.role").value("OPERATOR"));
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(marta)))
                .andExpect(jsonPath("$.user.displayName").value("Marta Rossi"));

        mockMvc.perform(put("/api/auth/profile").header(HttpHeaders.AUTHORIZATION, bearer(marta))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"" + "x".repeat(121) + "\"}"))
                .andExpect(status().isBadRequest());
        // A service token is not a person: it has no profile to rename.
        mockMvc.perform(put("/api/auth/profile").contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"x\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:not-a-user-session"));
    }

    @Test
    void anOperatorWorksButCannotManagePeopleReadTheLogOrDelete() throws Exception {
        String admin = tokenOf("admin", ADMIN_PASSWORD);
        createUser(admin, "paolo", "OPERATOR", "works-here-since-2026");
        String operator = tokenOf("paolo", "works-here-since-2026");

        mockMvc.perform(get("/api/projects").header(HttpHeaders.AUTHORIZATION, bearer(operator)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(operator)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:access-denied"));
        mockMvc.perform(get("/api/admin/security-events").header(HttpHeaders.AUTHORIZATION, bearer(operator)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tasks/1").header(HttpHeaders.AUTHORIZATION, bearer(operator)))
                .andExpect(status().isForbidden());

        // The default client of the suite is an OPERATOR service token: same rules.
        mockMvc.perform(get("/api/admin/users")).andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/admin/security-events").header(HttpHeaders.AUTHORIZATION, bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'ACCESS_DENIED')]").exists());
    }

    @Test
    void theLastAdminCannotBeDisabledOrDemotedAndADisabledUserLosesTheirSessions() throws Exception {
        String admin = tokenOf("admin", ADMIN_PASSWORD);
        long adminId = jdbc.queryForObject("SELECT id FROM app_users WHERE username = 'admin'", Long.class);
        long version = jdbc.queryForObject("SELECT version FROM app_users WHERE id = ?", Long.class, adminId);

        mockMvc.perform(put("/api/admin/users/" + adminId).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .header(HttpHeaders.IF_MATCH, "\"" + version + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\",\"enabled\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:last-admin"));

        long anna = createUser(admin, "anna", "OPERATOR", "secret-for-the-a-user");
        String annaSession = tokenOf("anna", "secret-for-the-a-user");
        long annaVersion = jdbc.queryForObject("SELECT version FROM app_users WHERE id = ?", Long.class, anna);
        mockMvc.perform(put("/api/admin/users/" + anna).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\",\"enabled\":false}"))
                .andExpect(status().isPreconditionRequired());
        mockMvc.perform(put("/api/admin/users/" + anna).header(HttpHeaders.AUTHORIZATION, bearer(admin))
                        .header(HttpHeaders.IF_MATCH, "\"" + annaVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"OPERATOR\",\"enabled\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer(annaSession)))
                .andExpect(status().isUnauthorized());
        login("anna", "secret-for-the-a-user").andExpect(status().isUnauthorized());
    }

    @Test
    void aForgedOrExpiredLookingSessionTokenIsJustAWrongToken() throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, bearer("aicos_s_forged-token-value-000000000000")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:unauthenticated"));
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    /** Spring Boot's default user would print a generated password to the log (found in the PHASE 27 smoke). */
    @Test
    void thereIsNoDefaultInMemoryUserWhosePasswordWouldReachTheLog() {
        assertThat(context.getBeanNamesForType(
                org.springframework.security.core.userdetails.UserDetailsService.class)).isEmpty();
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/projects"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
