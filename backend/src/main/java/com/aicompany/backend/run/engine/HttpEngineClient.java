package com.aicompany.backend.run.engine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

/**
 * Contract v1 over HTTP, with the JDK client (ADR-015 §3, ADR-016 §4).
 *
 * <p>Every way a call can end is mapped, and only these ways exist: a
 * {@link EngineClient.Completion}, the engine's own problem type relayed, or one
 * of {@link RunFailures}. Nothing escapes as an I/O exception -- an executor that
 * receives one cannot tell the operator anything about it.
 */
public class HttpEngineClient implements EngineClient {

    private static final String PROBLEM_JSON = "application/problem+json";

    private final EngineProperties properties;
    private final HttpClient http;
    private final JsonMapper json = JsonMapper.builder().build();

    public HttpEngineClient(EngineProperties properties) {
        if (properties.getToken() == null || properties.getToken().strip().length() < 16) {
            // Fail closed, like both sides of the boundary: an engine client
            // without a credential would send every run to a 401.
            throw new IllegalStateException(
                    "aicos.engine.token must be set to the engine's AICOS_ENGINE_TOKEN (at least 16 characters)");
        }
        this.properties = properties;
        this.http = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @Override
    public Completion complete(Request request) {

        ObjectNode body = json.createObjectNode();
        if (request.model() != null) {
            body.put("model", request.model());
        }
        body.put("system", request.system());
        body.putArray("messages").addObject().put("role", "user").put("content", request.user());
        body.put("max_tokens", request.maxTokens());
        ObjectNode metadata = body.putObject("metadata");
        request.metadata().forEach(metadata::put);

        HttpRequest http = HttpRequest.newBuilder(URI.create(properties.getUrl().replaceAll("/+$", "") + "/v1/completions"))
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + properties.getToken())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, " + PROBLEM_JSON)
                .header("X-Correlation-Id", request.correlationId())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = this.http.send(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpConnectTimeoutException | ConnectException e) {
            throw new EngineFailure(RunFailures.ENGINE_UNREACHABLE,
                    "Nothing answered at " + properties.getUrl() + "; is the AI Engine running?");
        } catch (HttpTimeoutException e) {
            throw new EngineFailure(RunFailures.ENGINE_TIMEOUT,
                    "The AI Engine did not answer within " + properties.getReadTimeout());
        } catch (IOException e) {
            throw new EngineFailure(RunFailures.ENGINE_UNREACHABLE,
                    "The connection to the AI Engine failed (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EngineFailure(RunFailures.INTERRUPTED, "The run was interrupted while waiting for the engine");
        }

        JsonNode answer;
        try {
            answer = json.readTree(response.body());
        } catch (RuntimeException e) {
            throw new EngineFailure(RunFailures.ENGINE_PROTOCOL,
                    "The AI Engine answered " + response.statusCode() + " with a body that is not JSON");
        }

        if (response.statusCode() != 200) {
            String type = text(answer, "type");
            if (type == null || !type.startsWith("urn:ai-company-os:engine:problem:")) {
                throw new EngineFailure(RunFailures.ENGINE_PROTOCOL,
                        "The AI Engine answered " + response.statusCode() + " without a problem type");
            }
            String detail = text(answer, "detail");
            throw new EngineFailure(type, detail == null ? "The AI Engine refused the completion" : detail);
        }

        String output = text(answer, "output");
        String finishReason = text(answer, "finish_reason");
        String model = text(answer, "model");
        JsonNode usage = answer.get("usage");
        if (output == null || finishReason == null || model == null || usage == null) {
            throw new EngineFailure(RunFailures.ENGINE_PROTOCOL, "The AI Engine answered 200 without the v1 fields");
        }
        return new Completion(output, finishReason, model,
                usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0),
                answer.path("latency_ms").asLong(0));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
