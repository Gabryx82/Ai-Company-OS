package com.aicompany.backend.usage.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The quota of a Kimi Code membership, asked to Kimi with the operator's own Kimi
 * Code key ({@code AICOS_KIMI_CODE_API_KEY} in local.env). It is the request the
 * official Kimi Code CLI makes for its {@code /usage} command:
 * {@code GET <base>/usages}, bearer key.
 *
 * <p>The answer holds the weekly quota ({@code usage}) and the rolling windows
 * ({@code limits}, each with its length and the same figures); numbers may come as
 * strings. Only figures and reset times are kept. The key goes to the configured
 * host only, over HTTPS (plain HTTP only to this machine, for tests), and is never
 * logged or returned.
 */
public class KimiCodeUsage {

    private static final Logger log = LoggerFactory.getLogger(KimiCodeUsage.class);
    private static final Duration CACHE = Duration.ofSeconds(60);
    private static final int MAX_BYTES = 256 * 1024;

    /** One quota: how much of it is used and when it resets. {@code windowMinutes} is null for the weekly total. */
    public record Quota(Integer windowMinutes, double usedPercent, Instant resetsAt) {
    }

    /** What Kimi answered, or why there is nothing. */
    public record Report(Instant observedAt, List<Quota> quotas, String problem) {

        static Report failed(Instant at, String problem) {
            return new Report(at, List.of(), problem);
        }

        public Optional<Quota> weekly() {
            return quotas.stream().filter(q -> q.windowMinutes() == null).findFirst();
        }

        public Optional<Quota> window(int minutes) {
            return quotas.stream().filter(q -> q.windowMinutes() != null && q.windowMinutes() == minutes).findFirst();
        }
    }

    private final URI usages;
    private final String apiKey;
    private final Clock clock;
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final JsonMapper json = JsonMapper.builder().build();

    private volatile Report cached;

    public KimiCodeUsage(String baseUrl, String apiKey, Clock clock) {
        this.usages = URI.create(baseUrl.strip().replaceAll("/+$", "") + "/usages");
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.clock = clock;
        boolean local = "localhost".equals(usages.getHost()) || "127.0.0.1".equals(usages.getHost());
        if (!"https".equals(usages.getScheme()) && !("http".equals(usages.getScheme()) && local)) {
            throw new IllegalArgumentException("The Kimi Code base URL must be https: " + baseUrl);
        }
    }

    public boolean configured() {
        return !apiKey.isEmpty();
    }

    /** The last answer, asked again at most once a minute. Empty when no key is configured. */
    public Optional<Report> report() {
        if (!configured()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Report last = cached;
        if (last != null && last.observedAt().plus(CACHE).isAfter(now)) {
            return Optional.of(last);
        }
        Report fresh = fetch(now);
        cached = fresh;
        return Optional.of(fresh);
    }

    private Report fetch(Instant now) {
        HttpRequest request = HttpRequest.newBuilder(usages)
                .timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readNBytes(MAX_BYTES);
            }
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                return Report.failed(now, "Kimi Code ha rifiutato la chiave (" + status + "): controlla AICOS_KIMI_CODE_API_KEY");
            }
            if (status != 200) {
                return Report.failed(now, "Kimi Code ha risposto " + status);
            }
            return parse(json.readTree(body), now);
        } catch (IOException e) {
            log.debug("Kimi Code usage unreachable: {}", e.toString());
            return Report.failed(now, "Kimi Code non raggiungibile");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Report.failed(now, "Richiesta a Kimi Code interrotta");
        } catch (RuntimeException e) {
            return Report.failed(now, "Risposta di Kimi Code non riconosciuta");
        }
    }

    static Report parse(JsonNode root, Instant now) {
        List<Quota> quotas = new ArrayList<>();
        quota(root.path("usage"), null).ifPresent(quotas::add);
        for (JsonNode limit : root.path("limits")) {
            JsonNode detail = limit.path("detail").isObject() ? limit.path("detail") : limit;
            Integer minutes = minutes(limit.path("window"));
            if (minutes != null) {
                quota(detail, minutes).ifPresent(quotas::add);
            }
        }
        if (quotas.isEmpty()) {
            return Report.failed(now, "Risposta di Kimi Code senza quote");
        }
        return new Report(now, quotas, null);
    }

    private static Optional<Quota> quota(JsonNode node, Integer windowMinutes) {
        if (!node.isObject()) {
            return Optional.empty();
        }
        Optional<Double> limit = number(node.path("limit"));
        Optional<Double> used = number(node.path("used"))
                .or(() -> limit.flatMap(l -> number(node.path("remaining")).map(r -> l - r)));
        if (limit.isEmpty() || used.isEmpty() || limit.get() <= 0) {
            return Optional.empty();
        }
        double percent = Math.round(Math.max(0, Math.min(1, used.get() / limit.get())) * 1000) / 10.0;
        return Optional.of(new Quota(windowMinutes, percent, instant(node)));
    }

    private static Integer minutes(JsonNode window) {
        Optional<Double> duration = number(window.path("duration"));
        if (duration.isEmpty()) {
            return null;
        }
        String unit = window.path("timeUnit").asString("").toUpperCase(Locale.ROOT);
        double factor = unit.contains("HOUR") ? 60 : unit.contains("DAY") ? 1440 : unit.contains("SECOND") ? 1 / 60.0 : 1;
        return (int) Math.round(duration.get() * factor);
    }

    private static Optional<Double> number(JsonNode node) {
        if (node.isNumber()) {
            return Optional.of(node.asDouble());
        }
        if (node.isString()) {
            try {
                return Optional.of(Double.parseDouble(node.asString().strip()));
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private static Instant instant(JsonNode node) {
        for (String name : List.of("resetTime", "resetAt", "reset_time", "reset_at")) {
            JsonNode value = node.path(name);
            if (value.isString()) {
                try {
                    return Instant.parse(value.asString().strip());
                } catch (DateTimeParseException e) {
                    // try the next spelling
                }
            }
        }
        return null;
    }
}
