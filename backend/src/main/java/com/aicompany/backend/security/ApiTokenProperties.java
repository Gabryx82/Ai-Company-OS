package com.aicompany.backend.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The credentials the control plane accepts, by name.
 *
 * <p>{@code aicos.security.api-tokens.<name>=<token>}. The name is the principal:
 * it is what the system will say did something, which is why it is chosen by
 * whoever configures the token and not derived from the token itself.
 *
 * <p>Configuration and not a table, deliberately (ADR-013 §2). A table needs a
 * way to put the first token in it, and every such way is either an
 * unauthenticated endpoint or a manual SQL step; configuration already has a
 * secure channel -- the environment -- and the operator already uses it for the
 * database password.
 */
@ConfigurationProperties(prefix = "aicos.security")
public class ApiTokenProperties {

    private Map<String, String> apiTokens = new LinkedHashMap<>();

    public Map<String, String> getApiTokens() {
        return apiTokens;
    }

    public void setApiTokens(Map<String, String> apiTokens) {
        this.apiTokens = apiTokens;
    }
}
