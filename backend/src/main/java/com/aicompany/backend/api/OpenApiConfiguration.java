package com.aicompany.backend.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI description of the control plane (TASK-021, M-3).
 *
 * <p>Served at {@code /v3/api-docs} to an authenticated caller only -- the same
 * rule as everything else (ADR-013): a map of the API is not something an
 * anonymous caller needs. Its committed copy, {@code docs/api/openapi.json}, is
 * what the console's types are generated from, and {@code OpenApiContractTest}
 * fails when the two drift.
 *
 * <p>What it cannot say, and the prose must: that every mutation needs
 * {@code If-Match} (ADR-009), and that errors are problem details with a stable
 * {@code type} (ADR-007). Both are stated in the description below.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    private static final String BEARER = "bearer";

    @Bean
    OpenAPI controlPlaneOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Company OS — control plane")
                        .version("v1")
                        .description("""
                                Every route needs `Authorization: Bearer <token>` (ADR-013). Every mutation of an \
                                existing resource needs `If-Match` with the ETag last read (ADR-009): absent is 428, \
                                stale is 412. Every error is `application/problem+json` with a stable \
                                `type` = `urn:ai-company-os:problem:<slug>` (ADR-007); branch on `type`, not on \
                                `title`."""))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }
}
