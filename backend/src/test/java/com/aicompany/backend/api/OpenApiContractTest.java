package com.aicompany.backend.api;

import com.aicompany.backend.support.AbstractPostgresTest;
import com.aicompany.backend.support.ScriptedEngineClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The OpenAPI description is complete, protected, and the committed copy is the
 * one the code produces (TASK-021).
 *
 * <p>{@code docs/api/openapi.json} is what the operator console generates its
 * types from. A copy that drifted from the code would give the console a contract
 * the server does not honour, silently -- so a difference turns this red. To
 * regenerate after an intended API change:
 *
 * <pre>./mvnw test -Dtest=OpenApiContractTest -Dopenapi.write=true</pre>
 */
class OpenApiContractTest extends AbstractPostgresTest {

    private static final String COMMITTED = "docs/api/openapi.json";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private ScriptedEngineClient engine;

    private final JsonMapper json = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Test
    void theDescriptionIsNotForAnonymousCallers() throws Exception {
        MockMvc anonymous = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        anonymous.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
    }

    @Test
    void everyApiRouteIsDescribedWithItsMethod() throws Exception {

        JsonNode paths = description().get("paths");

        List<String> missing = new ArrayList<>();
        for (RequestMappingInfo info : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                if (!pattern.startsWith("/api/")) {
                    continue;
                }
                for (RequestMethod method : info.getMethodsCondition().getMethods()) {
                    JsonNode path = paths.get(pattern);
                    if (path == null || path.get(method.name().toLowerCase(Locale.ROOT)) == null) {
                        missing.add(method + " " + pattern);
                    }
                }
            }
        }
        assertThat(missing).as("routes the description does not know about").isEmpty();
    }

    @Test
    void theCommittedDescriptionIsTheOneTheCodeProduces() throws Exception {

        String produced = normalised(description());
        Path committed = repositoryRoot().resolve(COMMITTED);

        if (Boolean.getBoolean("openapi.write")) {
            Files.createDirectories(committed.getParent());
            Files.writeString(committed, produced, StandardCharsets.UTF_8);
        }

        assertThat(Files.exists(committed))
                .as(COMMITTED + " is missing; generate it with -Dopenapi.write=true and commit it")
                .isTrue();
        assertThat(Files.readString(committed, StandardCharsets.UTF_8).replace("\r\n", "\n"))
                .as("""
                    %s has drifted from the code. If the API change is intended, regenerate it:
                      ./mvnw test -Dtest=OpenApiContractTest -Dopenapi.write=true
                    and regenerate the console's types (frontend: npm run api:types).""".formatted(COMMITTED))
                .isEqualTo(produced);
    }

    // --- the engine's models, relayed ---------------------------------------

    @Test
    void theEnginesModelsAreRelayedWithoutItsToken() throws Exception {

        mockMvc.perform(get("/api/engine/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultModel").value("echo:default"))
                .andExpect(jsonPath("$.models[0].id").value("echo:default"))
                .andExpect(jsonPath("$.models[1].billed").value(true));
    }

    @Test
    void anEngineThatDoesNotAnswerIsA503InsideTheContract() throws Exception {

        engine.modelsUnavailable();
        mockMvc.perform(get("/api/engine/models"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value("urn:ai-company-os:problem:engine-unavailable"));
    }

    // --- helpers -----------------------------------------------------------

    private JsonNode description() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body);
    }

    /** Keys sorted and the server URL dropped, so the file changes only when the contract does. */
    private String normalised(JsonNode description) {
        tools.jackson.databind.node.ObjectNode copy = (tools.jackson.databind.node.ObjectNode) description.deepCopy();
        copy.remove("servers");
        Object tree = json.convertValue(copy, Object.class);
        // LF everywhere: the pretty printer uses the platform separator, and the
        // file must be byte-identical on Windows and on the Linux CI runner.
        return json.writeValueAsString(sortRecursively(tree)).replace("\r\n", "\n") + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Object sortRecursively(Object value) {
        if (value instanceof java.util.Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), sortRecursively(v)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(OpenApiContractTest::sortRecursively).toList();
        }
        return value;
    }

    private static Path repositoryRoot() throws IOException {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null) {
            if (Files.isDirectory(candidate.resolve(".company-os"))) {
                return candidate;
            }
            candidate = candidate.getParent();
        }
        throw new IOException("repository root not found");
    }
}
