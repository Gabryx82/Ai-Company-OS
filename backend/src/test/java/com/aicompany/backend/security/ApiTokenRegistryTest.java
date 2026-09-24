package com.aicompany.backend.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The registry fails closed: every configuration that would leave the control
 * plane running without a usable credential stops it from starting instead.
 */
class ApiTokenRegistryTest {

    private static final String OPERATOR_TOKEN = "operator-token-0123456789";
    private static final String ENGINE_TOKEN = "engine-token-0123456789ab";

    /**
     * PHASE 15 (ADR-024): people sign in, so a service token is optional. No
     * token configured means no machine can call -- never an open API.
     */
    @Test
    void noConfiguredTokenMeansNoServiceCallerNotAnOpenApi() {

        assertThat(new ApiTokenRegistry(Map.of()).principalFor(OPERATOR_TOKEN)).isEmpty();
        assertThat(new ApiTokenRegistry(null).principals()).isEmpty();
        // An unset environment variable resolves to a blank value: "not configured".
        assertThat(new ApiTokenRegistry(Map.of("operator", "   ")).principals()).isEmpty();
        assertThat(new ApiTokenRegistry(Map.of("operator", "   ")).principalFor("   ")).isEmpty();
    }

    @Test
    void aTokenTooShortToBeACredentialIsRefused() {

        assertThatThrownBy(() -> new ApiTokenRegistry(Map.of("operator", "short")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("operator")
                .hasMessageContaining(String.valueOf(ApiTokenRegistry.MINIMUM_TOKEN_LENGTH));
    }

    /** Otherwise the principal of a request would depend on map iteration order. */
    @Test
    void twoNamesCannotShareOneToken() {

        Map<String, String> tokens = new LinkedHashMap<>();
        tokens.put("operator", OPERATOR_TOKEN);
        tokens.put("engine", OPERATOR_TOKEN);

        assertThatThrownBy(() -> new ApiTokenRegistry(tokens))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another name");
    }

    @Test
    void eachTokenResolvesToItsOwnNameAndNothingElseResolves() {

        ApiTokenRegistry registry = new ApiTokenRegistry(Map.of(
                "operator", OPERATOR_TOKEN,
                "engine", ENGINE_TOKEN));

        assertThat(registry.principalFor(OPERATOR_TOKEN)).contains("operator");
        assertThat(registry.principalFor(ENGINE_TOKEN)).contains("engine");

        assertThat(registry.principalFor(OPERATOR_TOKEN + "x")).isEmpty();
        assertThat(registry.principalFor(OPERATOR_TOKEN.substring(1))).isEmpty();
        assertThat(registry.principalFor("")).isEmpty();
        assertThat(registry.principalFor(null)).isEmpty();

        assertThat(registry.principals()).containsExactlyInAnyOrder("operator", "engine");
    }
}
