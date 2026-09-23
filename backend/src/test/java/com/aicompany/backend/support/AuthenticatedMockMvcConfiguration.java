package com.aicompany.backend.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Makes the shared {@code MockMvc} an authenticated operator.
 *
 * <p>TASK-013 put every {@code /api/**} route behind a bearer token, and the
 * known cost of TD-04 was that it "would change every API test". It changes
 * none of them: the credential is a default of the client, the way it is for a
 * real one, and a test that is about archiving a project keeps asserting about
 * archiving a project.
 *
 * <p>The tests that <em>are</em> about authentication do not use this client:
 * {@code ApiAuthenticationContractTest} builds its own, with no default header,
 * because a default cannot be removed from a request -- only overridden.
 */
@TestConfiguration(proxyBeanMethods = false)
public class AuthenticatedMockMvcConfiguration {

    /** Must match {@code aicos.security.api-tokens.operator} in application-test.properties. */
    public static final String OPERATOR_TOKEN = "test-operator-token-0123456789";

    @Bean
    MockMvcBuilderCustomizer authenticatedAsOperator() {
        return builder -> builder.defaultRequest(
                get("/").header(HttpHeaders.AUTHORIZATION, "Bearer " + OPERATOR_TOKEN));
    }
}
