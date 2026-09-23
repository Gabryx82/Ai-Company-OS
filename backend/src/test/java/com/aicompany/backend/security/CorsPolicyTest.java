package com.aicompany.backend.security;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AuthenticatedMockMvcConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-11: a browser may talk to the control plane from the origins somebody
 * declared, and from nowhere else.
 *
 * <p>The assertion that matters most is not about origins. It is
 * {@link #theEntityTagIsReadableFromJavaScript()}: without
 * {@code Access-Control-Expose-Headers: ETag} a browser hides the header from
 * script, and a browser client could never send the {@code If-Match} that every
 * mutation requires (ADR-009). CORS that lets the request through but hides the
 * tag is a console that can read and never write.
 */
class CorsPolicyTest extends AbstractPostgresTest {

    /** Declared in application-test.properties. */
    private static final String CONSOLE = "http://console.test";
    private static final String STRANGER = "http://elsewhere.example";

    @Autowired
    private WebApplicationContext context;

    private MockMvc browser;

    @BeforeEach
    void browserWithoutDefaults() {
        // No default Authorization: a preflight never carries one.
        browser = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void aPreflightFromTheDeclaredOriginIsAllowedWithoutACredential() throws Exception {

        browser.perform(options("/api/tasks/{id}/project", 1L)
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PUT")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,if-match"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("PUT")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("if-match")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("authorization")));
    }

    @Test
    void aPreflightFromAnUndeclaredOriginIsRefused() throws Exception {

        browser.perform(options("/api/projects")
                        .header(HttpHeaders.ORIGIN, STRANGER)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void theEntityTagIsReadableFromJavaScript() throws Exception {

        browser.perform(get("/api/projects")
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("ETag")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString("Location")));
    }

    /**
     * A declared origin is not a credential. CORS decides what a browser lets a
     * page <em>read</em>; it does not authenticate anybody.
     */
    @Test
    void aDeclaredOriginStillNeedsAToken() throws Exception {

        browser.perform(get("/api/projects").header(HttpHeaders.ORIGIN, CONSOLE))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE));
    }

    /**
     * No cookies, so no credentialed CORS: the browser must never attach anything
     * by itself. That is what keeps CSRF irrelevant (ADR-013 §3).
     */
    @Test
    void credentialedRequestsAreNotAllowed() throws Exception {

        browser.perform(options("/api/projects")
                        .header(HttpHeaders.ORIGIN, CONSOLE)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    /** Found by running the console against a live backend: its status light read this route. */
    @Test
    void theLivenessProbeIsReadableByTheConsole() throws Exception {

        browser.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, CONSOLE))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, CONSOLE));

        browser.perform(get("/actuator/health").header(HttpHeaders.ORIGIN, STRANGER))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void theWildcardIsNotAnOriginAndStopsTheApplication() {

        assertThatThrownBy(() -> CorsPolicy.from(List.of("http://console.test", "*")))
                .isInstanceOf(IllegalStateException.class)
                // The reason, not just the value: "*" also fails the origin check
                // below, and an operator told only "not an origin" would try "https://*".
                .hasMessageContaining("wildcards are refused");

        assertThatThrownBy(() -> CorsPolicy.from(List.of("https://*.example.com")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wildcards are refused");

        assertThatThrownBy(() -> CorsPolicy.from(List.of("console.test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("console.test");
    }
}
