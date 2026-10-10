package com.aicompany.backend.security;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AuthenticatedMockMvcConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PHASE 32 (TD-53; ADR-024 §8 revised): the console's session token lives in an
 * {@code HttpOnly} cookie that no script can read, and every write made with it
 * carries the session's anti-forgery header.
 *
 * <p>The client here is a browser: no default {@code Authorization}, only what
 * the test attaches -- the cookie, and the header when the page would send it.
 */
class BrowserSessionApiTest extends AbstractPostgresTest {

    private static final String PASSWORD = "test-root-secret-0123";

    /** MockMvc's requests arrive on port 80: the cookie's name carries it. */
    private static final String COOKIE = "aicos_session_80";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();
    private MockMvc browser;

    @BeforeEach
    void aBrowserWithNothingAttached() {
        browser = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        jdbc.update("UPDATE app_users SET failed_attempts = 0, locked_until = NULL WHERE username = 'admin'");
        jdbc.update("DELETE FROM security_events");
    }

    private record Signed(Cookie cookie, String csrf, String setCookie, String body) {
    }

    private Signed signIn() throws Exception {
        MvcResult result = browser.perform(post("/api/auth/session").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie(COOKIE);
        assertThat(cookie).as("the session cookie").isNotNull();
        String body = result.getResponse().getContentAsString();
        return new Signed(cookie, json.readTree(body).path("csrfToken").asString(),
                result.getResponse().getHeader(HttpHeaders.SET_COOKIE), body);
    }

    @Test
    void signingInPutsTheTokenInAnHttpOnlyStrictCookieAndNeverInTheBody() throws Exception {
        Signed signed = signIn();

        assertThat(signed.setCookie()).contains(COOKIE + "=aicos_s_", "HttpOnly", "SameSite=Strict", "Path=/api")
                .doesNotContain("Max-Age");
        JsonNode body = json.readTree(signed.body());
        assertThat(body.has("token")).isFalse();
        assertThat(signed.body()).doesNotContain(signed.cookie().getValue());
        assertThat(body.path("user").path("username").asString()).isEqualTo("admin");
        assertThat(signed.csrf()).isNotBlank().isNotEqualTo(signed.cookie().getValue());
        assertThat(jdbc.queryForList("SELECT type FROM security_events", String.class)).contains("LOGIN_SUCCEEDED");
    }

    @Test
    void theCookieReadsButAWriteNeedsTheSessionsAntiForgeryHeader() throws Exception {
        Signed signed = signIn();
        String project = "{\"name\":\"Scritto dal browser\"}";

        browser.perform(get("/api/auth/me").cookie(signed.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("admin"));

        browser.perform(post("/api/projects").cookie(signed.cookie()).contentType(MediaType.APPLICATION_JSON).content(project))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:csrf-rejected"));
        browser.perform(post("/api/projects").cookie(signed.cookie()).header(SessionCookie.CSRF_HEADER, "forged")
                        .contentType(MediaType.APPLICATION_JSON).content(project))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:csrf-rejected"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM projects WHERE name = 'Scritto dal browser'", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT detail FROM security_events WHERE type = 'ACCESS_DENIED'", String.class))
                .hasSize(2).allSatisfy(detail -> assertThat(detail).startsWith("CSRF: POST /api/projects"));

        browser.perform(post("/api/projects").cookie(signed.cookie()).header(SessionCookie.CSRF_HEADER, signed.csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(project))
                .andExpect(status().isCreated());
        jdbc.update("DELETE FROM projects WHERE name = 'Scritto dal browser'");
    }

    @Test
    void anotherTabGetsTheAntiForgeryTokenOfTheCookieTheBrowserHolds() throws Exception {
        Signed signed = signIn();

        browser.perform(get("/api/auth/session").cookie(signed.cookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").value(signed.csrf()))
                .andExpect(jsonPath("$.user.username").value("admin"));

        // A bearer caller has no browser session to describe.
        browser.perform(get("/api/auth/session")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:not-a-browser-session"));
        browser.perform(get("/api/auth/session")).andExpect(status().isUnauthorized());
    }

    /**
     * A tab on the sign-in page while the browser still holds a live cookie (another
     * tab, another control-plane address): the sign-in is public and does not ask for
     * the anti-forgery token of a cookie this page never saw.
     */
    @Test
    void signingInAgainWhileTheBrowserHoldsALiveCookieWorks() throws Exception {
        Signed first = signIn();

        MvcResult again = browser.perform(post("/api/auth/session").cookie(first.cookie())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie second = again.getResponse().getCookie(COOKIE);
        assertThat(second).isNotNull();
        assertThat(second.getValue()).isNotEqualTo(first.cookie().getValue());
    }

    @Test
    void signingOutEndsTheSessionAndExpiresTheCookie() throws Exception {
        Signed signed = signIn();

        MvcResult out = browser.perform(post("/api/auth/logout").cookie(signed.cookie())
                        .header(SessionCookie.CSRF_HEADER, signed.csrf()))
                .andExpect(status().isNoContent()).andReturn();
        assertThat(out.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains(COOKIE + "=;", "Max-Age=0", "HttpOnly");

        browser.perform(get("/api/auth/me").cookie(signed.cookie())).andExpect(status().isUnauthorized());
    }

    /**
     * A cookie is attached whether or not it still means anything: one left by a
     * session that ended -- or by another control plane -- is ignored, so the
     * sign-in still answers and a protected route says 401, not "bad cookie".
     */
    @Test
    void aStaleCookieIsIgnoredAndNeverCarriesAServiceToken() throws Exception {
        Cookie stale = new Cookie(COOKIE, "aicos_s_not-a-session-of-ours");

        browser.perform(get("/api/projects").cookie(stale)).andExpect(status().isUnauthorized());
        browser.perform(post("/api/auth/session").cookie(stale).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());

        // A public route other than the sign-in -- the terminal's handshake, which carries its own ticket
        // (ADR-029) and sits under the cookie's path -- is not turned into a 401 by a cookie that means nothing.
        MvcResult handshake = browser.perform(get(com.aicompany.backend.terminal.TerminalConfiguration.SOCKET_PATH)
                .cookie(stale)).andReturn();
        assertThat(handshake.getResponse().getStatus()).isNotEqualTo(401);

        Cookie serviceToken = new Cookie(COOKIE, AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN);
        browser.perform(get("/api/projects").cookie(serviceToken)).andExpect(status().isUnauthorized());

        // A cookie named for another port is another control plane's.
        Cookie otherPort = new Cookie("aicos_session_8091", signIn().cookie().getValue());
        browser.perform(get("/api/projects").cookie(otherPort)).andExpect(status().isUnauthorized());
    }
}
