package com.aicompany.backend.api;

import com.aicompany.backend.support.ResponseContractRecorder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PHASE 32 (TD-47): the contract the console's types are generated from says
 * which response fields are always there and which may be {@code null}
 * ({@link ResponseShapes}), and every JSON response the suite produced honours it.
 *
 * <p>Runs after the other classes, like {@code RouteCoverageTest}. Unlike that
 * one it is meaningful on part of the suite too -- it checks the responses that
 * were seen, and a partial run only sees fewer. A record component that can be
 * null without {@code @Nullable} turns the suite red at the first test that shows it.
 */
@Order(Integer.MAX_VALUE)
class ResponseContractTest {

    @Test
    void td47_everyResponseOfTheSuiteHonoursTheNullabilityOfTheContract() {
        Assumptions.assumeTrue(ResponseContractRecorder.checked() > 0, "no API test ran in this JVM");

        assertThat(ResponseContractRecorder.violations()).as("responses that break the contract").isEmpty();
    }

    @Test
    void td47_responseRecordsAreFullyRequiredAndNullableOnlyWhereDeclared() throws Exception {
        JsonNode schemas = JsonMapper.builder().build().readTree(Files.readString(Path.of("../docs/api/openapi.json")))
                .path("components").path("schemas");

        JsonNode run = schemas.path("RunResponse");
        assertThat(names(run.path("required"))).as("a record always sends every component")
                .containsAll(names(run.path("properties").propertyNames()));
        assertThat(run.path("properties").path("finishedAt").path("type").toString()).contains("null");
        assertThat(run.path("properties").path("status").path("type").toString()).doesNotContain("null");
        assertThat(schemas.path("DailyResponse").path("properties").path("task").path("anyOf").toString())
                .as("a nullable reference is a union with null").contains("TaskRef").contains("\"null\"");

        // A request body keeps springdoc's rules: a client may leave an optional field out.
        assertThat(names(schemas.path("TaskCreateRequest").path("required")))
                .containsExactlyInAnyOrder("priority", "status", "title");
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(n -> names.add(n.asString()));
        return names;
    }

    private static List<String> names(java.util.Collection<String> keys) {
        return List.copyOf(keys);
    }
}
