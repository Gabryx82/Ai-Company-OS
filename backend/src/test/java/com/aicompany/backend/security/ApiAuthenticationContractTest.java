package com.aicompany.backend.security;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.AuthenticatedMockMvcConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-04, made falsifiable: nothing under {@code /api/**} answers a caller the
 * control plane cannot name.
 *
 * <p>Uses its own {@code MockMvc}, built with no default header -- the shared one
 * is authenticated as the operator for every other test, and a default header
 * cannot be taken off a request, only overridden.
 *
 * <p>The one to read first is {@link #everyApiRouteRefusesAnAnonymousCaller()}.
 * It does not sample: it asks the handler mapping for every route that exists and
 * sends each one an anonymous request. That is the same shape as
 * {@code PreconditionCoverageTest}, and for the same reason -- a security rule
 * that holds on the routes somebody remembered to test is a rule a client cannot
 * rely on, and the way it breaks is that a new controller arrives and nothing
 * turns red.
 */
class ApiAuthenticationContractTest extends AbstractPostgresTest {

    private static final String UNAUTHENTICATED = "urn:ai-company-os:problem:unauthenticated";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private MockMvc anonymous;

    @BeforeEach
    void anonymousClient() {
        anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    // ------------------------------------------------------------------
    // Coverage: every route, not a sample
    // ------------------------------------------------------------------

    @Test
    void everyApiRouteRefusesAnAnonymousCaller() throws Exception {

        List<MockHttpServletRequestBuilder> routes = everyApiRoute();

        assertThat(routes)
                .as("the scan must find the API, or this test proves nothing")
                .hasSizeGreaterThanOrEqualTo(18);

        List<String> open = new ArrayList<>();
        for (MockHttpServletRequestBuilder route : routes) {
            MvcResult result = anonymous.perform(route).andReturn();
            if (result.getResponse().getStatus() != 401) {
                open.add(describe(result) + " -> " + result.getResponse().getStatus());
            }
        }

        assertThat(open)
                .as("""
                    TD-04. Every /api route must refuse a caller that presents no credential. \
                    A route listed here answers anonymous requests: either it was added \
                    outside the security configuration, or the configuration stopped \
                    covering /api/**.""")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // The shape of the refusal: inside ADR-007
    // ------------------------------------------------------------------

    @Test
    void theRefusalIsAProblemDetailWithAStableTypeAndABearerChallenge() throws Exception {

        anonymous.perform(get("/api/projects"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").isNotEmpty())
                .andExpect(jsonPath("$.detail").isNotEmpty());
    }

    /**
     * A wrong token and no token are the same answer. Distinguishing them would
     * tell a caller probing for tokens that it has found the right header and only
     * the value is wrong -- information nobody needs in order to fix a client that
     * sends the right one.
     */
    @Test
    void aWrongTokenIsRefusedExactlyLikeNoToken() throws Exception {

        String missing = anonymous.perform(get("/api/agents"))
                .andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();

        String wrong = anonymous.perform(get("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-the-operator-token-at-all"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED))
                .andReturn().getResponse().getContentAsString();

        assertThat(wrong).isEqualTo(missing);
    }

    @Test
    void theRightTokenUnderAnotherSchemeIsNotACredential() throws Exception {

        anonymous.perform(get("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED));

        anonymous.perform(get("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    /** RFC 6750 §2.1 via RFC 9110 §11.1: the scheme name is case-insensitive. */
    @Test
    void theSchemeIsCaseInsensitiveAndTheTokenIsNot() throws Exception {

        anonymous.perform(get("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION, "bearer " + AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN))
                .andExpect(status().isOk());

        anonymous.perform(get("/api/agents")
                        .header(HttpHeaders.AUTHORIZATION,
                                "Bearer " + AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN.toUpperCase()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theOperatorTokenIsAccepted() throws Exception {

        anonymous.perform(get("/api/projects")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + AuthenticatedMockMvcConfiguration.OPERATOR_TOKEN))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------
    // Ordering against the rest of the contract
    // ------------------------------------------------------------------

    /**
     * Authentication comes before existence. An anonymous caller must not be able
     * to learn which identifiers exist by comparing 404 with anything else.
     */
    @Test
    void anAnonymousCallerCannotLearnWhetherAResourceExists() throws Exception {

        anonymous.perform(get("/api/tasks/{id}", 987654321L))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED));

        anonymous.perform(get("/api/no-such-route"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Authentication comes before the precondition protocol: a 428 to an anonymous
     * caller would be telling it how to write.
     */
    @Test
    void anAnonymousWriteIsRefusedBeforeItsPreconditionIsLookedAt() throws Exception {

        anonymous.perform(put("/api/projects/{id}", 1L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.type").value(UNAUTHENTICATED));

        anonymous.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Created by nobody\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // What stays open, and only that
    // ------------------------------------------------------------------

    /**
     * Liveness has to be answerable by something that holds no credential -- a
     * container healthcheck, a start script waiting for readiness. It says "UP" and
     * nothing else: no components, no details, no version.
     */
    @Test
    void healthIsPublicAndSaysNothingElse() throws Exception {

        String body = anonymous.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("components").doesNotContain("details");
    }

    @Test
    void noOtherActuatorEndpointIsReachableAnonymously() throws Exception {

        for (String path : List.of("/actuator", "/actuator/env", "/actuator/beans", "/actuator/configprops",
                "/actuator/mappings", "/actuator/loggers", "/actuator/heapdump")) {
            int code = anonymous.perform(get(path)).andReturn().getResponse().getStatus();
            assertThat(code).as(path).isIn(401, 404);
        }
    }

    // --- helpers -----------------------------------------------------------

    /**
     * One concrete request per registered {@code /api} route and method. Path
     * variables become {@code 1}: authentication must be decided before anything
     * reads them, so their value cannot matter.
     */
    private List<MockHttpServletRequestBuilder> everyApiRoute() {

        List<MockHttpServletRequestBuilder> requests = new ArrayList<>();

        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {

            Set<String> patterns = info.getPatternValues();
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();

            for (String pattern : patterns) {
                if (!pattern.startsWith("/api/") && !pattern.equals("/api")) {
                    continue;
                }
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                for (RequestMethod method : methods.isEmpty() ? Set.of(RequestMethod.GET) : methods) {
                    requests.add(request(HttpMethod.valueOf(method.name()), path)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"));
                }
            }
        }
        return requests;
    }

    private static String describe(MvcResult result) {
        return result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
    }
}
