package io.kestra.plugin.browseruse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientRequestException;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

import org.slf4j.Logger;

/**
 * Calls the Browser Use API v4. One client is opened per run and closed before the task returns.
 */
final class BrowserUseClient implements AutoCloseable {
    static final int MAX_ATTEMPTS = 5;

    private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);
    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(2);
    private static final int ERROR_SNIPPET_LIMIT = 500;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final String apiKey;
    private final Logger logger;
    private final BooleanSupplier cancelled;

    private BrowserUseClient(
        HttpClient httpClient,
        String baseUrl,
        String apiKey,
        Logger logger,
        BooleanSupplier cancelled
    ) {
        this.httpClient = httpClient;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.logger = logger;
        this.cancelled = cancelled;
    }

    static BrowserUseClient open(
        RunContext runContext,
        String baseUrl,
        String apiKey,
        Logger logger,
        BooleanSupplier cancelled,
        Duration connectTimeout,
        Duration readTimeout
    ) throws Exception {
        var configuration = HttpConfiguration.builder()
            .allowFailed(Property.ofValue(true))
            .timeout(TimeoutConfiguration.builder()
                .connectTimeout(Property.ofValue(connectTimeout))
                .readIdleTimeout(Property.ofValue(readTimeout))
                .build())
            .build();
        return new BrowserUseClient(new HttpClient(runContext, configuration), baseUrl, apiKey, logger, cancelled);
    }

    RunPayload createRun(String prompt, String model, BigDecimal maxCostUsd) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("task", prompt);
        if (model != null) {
            body.put("model", model);
        }
        if (maxCostUsd != null) {
            body.put("maxCostUsd", maxCostUsd);
        }
        return send("POST", "/runs", body, "POST /runs", true);
    }

    String readStatus(String runId) throws Exception {
        var payload = send("GET", runPath(runId) + "/status", null, "GET /runs/" + runId + "/status", false);
        if (payload.status() == null || payload.status().isBlank()) {
            throw new IllegalStateException(
                "Browser Use returned no status for run " + runId
                    + ". Check the run in the Browser Use dashboard before starting another one."
            );
        }
        return payload.status();
    }

    RunPayload readRun(String runId) throws Exception {
        return send("GET", runPath(runId), null, "GET /runs/" + runId, false);
    }

    /**
     * Best-effort cancel. A 404 means the run is already gone.
     */
    void cancel(String runId) throws Exception {
        var result = execute(request("POST", runPath(runId) + "/cancel", null));
        if (result.code() == 404 || result.code() < 300) {
            return;
        }
        throw httpFailure("POST /runs/" + runId + "/cancel", result.code(), result.body(), false);
    }

    private RunPayload send(String method, String path, Map<String, Object> body, String label, boolean createCall) throws Exception {
        var attempt = 1;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new InterruptedException("Task was killed");
            }
            HttpResult result;
            try {
                result = execute(request(method, path, body));
            } catch (HttpClientRequestException e) {
                if (!retryTransport(createCall, attempt)) {
                    throw transportFailure(label, e, createCall);
                }
                logger.warn(
                    "Browser Use {} failed ({}). Retrying attempt {} of {}.",
                    label,
                    reason(e),
                    attempt,
                    MAX_ATTEMPTS
                );
                pause(DEFAULT_RETRY_DELAY);
                attempt++;
                continue;
            } catch (RuntimeException e) {
                if (!(e.getCause() instanceof IOException)) {
                    throw e;
                }
                if (!retryTransport(createCall, attempt)) {
                    throw transportFailure(label, e, createCall);
                }
                logger.warn(
                    "Browser Use {} failed ({}). Retrying attempt {} of {}.",
                    label,
                    reason(e),
                    attempt,
                    MAX_ATTEMPTS
                );
                pause(DEFAULT_RETRY_DELAY);
                attempt++;
                continue;
            }

            // GET is safe to repeat. POST /runs is not: a 5xx or a dropped response may already have created the run.
            // 429 is retried for both. The HTTP client may also retry 429 once before this loop sees it.
            if (retryStatus(result.code(), createCall)) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw httpFailure(label, result.code(), result.body(), createCall);
                }
                var wait = retryDelay(result);
                logger.warn(
                    "Browser Use {} returned {}. Waiting {} before retry {} of {}.",
                    label,
                    result.code(),
                    wait,
                    attempt,
                    MAX_ATTEMPTS
                );
                pause(wait);
                attempt++;
                continue;
            }

            if (result.code() >= 400) {
                throw httpFailure(label, result.code(), result.body(), createCall);
            }

            if (result.body() == null || result.body().isBlank()) {
                if (createCall) {
                    throw new IllegalStateException(
                        "Browser Use returned an empty body when creating a run. The run may already exist. "
                            + "Check the Browser Use dashboard before starting another one."
                    );
                }
                throw new IllegalStateException("Browser Use API returned an empty body for " + label + ".");
            }

            return parse(result.body(), label);
        }
    }

    private HttpRequest request(String method, String path, Map<String, Object> body) {
        var builder = HttpRequest.builder()
            .method(method)
            .uri(URI.create(baseUrl + path))
            .addHeader("X-Browser-Use-API-Key", apiKey)
            .addHeader("Accept", "application/json");
        if (body != null) {
            builder.body(HttpRequest.JsonRequestBody.of(body));
        }
        return builder.build();
    }

    private HttpResult execute(HttpRequest request) throws Exception {
        var response = httpClient.request(request, String.class);
        var retryAfter = response.getHeaders() == null
            ? Optional.<String>empty()
            : response.getHeaders().firstValue("Retry-After");
        return new HttpResult(response.getStatus().getCode(), response.getBody(), retryAfter);
    }

    private Duration retryDelay(HttpResult result) {
        if (result.retryAfter().isPresent()) {
            var parsed = parseRetryAfter(result.retryAfter().get());
            if (parsed != null) {
                return cap(parsed);
            }
        }
        if (result.body() != null && !result.body().isBlank()) {
            try {
                var field = JacksonMapper.ofJson().readTree(result.body()).get("retry_after_seconds");
                if (field != null && field.isNumber()) {
                    return cap(Duration.ofSeconds(Math.max(0, field.asLong())));
                }
            } catch (Exception ignored) {
                // A non-JSON 429 body falls through to the default delay.
            }
        }
        return DEFAULT_RETRY_DELAY;
    }

    private Duration cap(Duration delay) {
        if (delay.compareTo(MAX_RETRY_DELAY) > 0) {
            logger.warn(
                "Browser Use asked us to wait {}, which is above {}. Waiting {} instead.",
                delay,
                MAX_RETRY_DELAY,
                MAX_RETRY_DELAY
            );
            return MAX_RETRY_DELAY;
        }
        return delay.isNegative() ? Duration.ZERO : delay;
    }

    private static Duration parseRetryAfter(String header) {
        var raw = header.trim();
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(raw)));
        } catch (NumberFormatException ignored) {
            try {
                var when = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
                return Duration.between(Instant.now(), when.toInstant());
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private void pause(Duration delay) throws InterruptedException {
        var end = System.nanoTime() + delay.toNanos();
        while (System.nanoTime() < end) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            var remainingMs = (end - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return;
            }
            Thread.sleep(Math.min(200L, remainingMs));
        }
    }

    private static RunPayload parse(String body, String label) {
        final JsonNode node;
        try {
            node = JacksonMapper.ofJson().readTree(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Browser Use returned a response for " + label + " that is not valid JSON. " + snippet(body),
                e
            );
        }
        if (!node.isObject()) {
            throw new IllegalStateException(
                "Browser Use returned a response for " + label + " that is not a JSON object. " + snippet(body)
            );
        }
        return new RunPayload(
            text(node, "id"),
            text(node, "status"),
            text(node, "result"),
            text(node, "error"),
            longValue(node, "totalInputTokens"),
            longValue(node, "totalOutputTokens"),
            cost(node)
        );
    }

    private static String runPath(String runId) {
        return "/runs/" + URLEncoder.encode(runId, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String text(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            var text = value.asText();
            return text.isBlank() ? null : text;
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return value.toString();
    }

    private static Long longValue(JsonNode node, String field) {
        var value = node.get(field);
        if (value == null || value.isNull() || !value.isNumber()) {
            return null;
        }
        return value.asLong();
    }

    private static String cost(JsonNode node) {
        var value = node.get("totalCostUsd");
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.decimalValue().toPlainString();
        }
        var text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static IllegalStateException httpFailure(String label, int code, String body, boolean createCall) {
        var hint = switch (code) {
            case 401, 403 -> "Check that the API key is valid and that the project is allowed to use API v4. "
                + "Zero Data Retention projects cannot use this API.";
            case 402 -> "The project has no credits, or this API key reached its monthly spend limit. "
                + "Add credits or raise the limit, then retry.";
            case 404 -> "The run was not found. Check the run id and the API base URL.";
            case 409 -> "The session already has an active run. Wait for it to finish or cancel it in the Browser Use dashboard.";
            case 400, 422 -> "The request was rejected. Check the prompt, model, and maxCostUsd.";
            case 429 -> "The project is at its request rate or concurrency limit. Wait and retry the task, or raise the project concurrency.";
            default -> createCall
                ? "The run may already exist. Check the Browser Use dashboard before starting another one, so you do not bill a second run."
                : "The read was retried and still failed.";
        };
        return new IllegalStateException("Browser Use " + label + " returned " + code + " (" + errorDetail(body) + "). " + hint);
    }

    private static boolean retryTransport(boolean createCall, int attempt) {
        return !createCall && attempt < MAX_ATTEMPTS;
    }

    private static boolean retryStatus(int code, boolean createCall) {
        if (code == 429) {
            return true;
        }
        return !createCall && code >= 500;
    }

    private static String reason(Exception exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }

    // A transport error on POST /runs is not retried: the run may already exist, and a second call would bill twice.
    private static IllegalStateException transportFailure(String label, Exception exception, boolean createCall) {
        var reason = reason(exception);
        if (createCall) {
            return new IllegalStateException(
                "Creating the Browser Use run failed (" + reason + ") and was not retried, because a retry could start a second billed run. "
                    + "Check the Browser Use dashboard for a run that may already exist, then retry this task only if none does.",
                exception
            );
        }
        return new IllegalStateException(
            "Browser Use " + label + " failed (" + reason + ") after " + MAX_ATTEMPTS + " attempts.",
            exception
        );
    }

    private static String errorDetail(String body) {
        if (body == null || body.isBlank()) {
            return "no response body";
        }
        try {
            var node = JacksonMapper.ofJson().readTree(body);
            var detail = node.get("detail");
            if (detail != null && detail.isTextual()) {
                return detail.asText();
            }
            if (detail != null && detail.isArray()) {
                var messages = new StringBuilder();
                for (var item : detail) {
                    if (item.hasNonNull("msg")) {
                        if (!messages.isEmpty()) {
                            messages.append("; ");
                        }
                        messages.append(item.get("msg").asText());
                    }
                }
                if (!messages.isEmpty()) {
                    return messages.toString();
                }
            }
            if (node.hasNonNull("error") && node.get("error").isTextual()) {
                return node.get("error").asText();
            }
        } catch (Exception ignored) {
            // Fall through to a trimmed copy of the raw body.
        }
        return snippet(body);
    }

    private static String snippet(String body) {
        var compact = body.replaceAll("\\s+", " ").trim();
        if (compact.length() <= ERROR_SNIPPET_LIMIT) {
            return compact;
        }
        return compact.substring(0, ERROR_SNIPPET_LIMIT) + "...";
    }

    @Override
    public void close() throws Exception {
        httpClient.close();
    }

    private record HttpResult(int code, String body, Optional<String> retryAfter) {
    }

    record RunPayload(
        String id,
        String status,
        String result,
        String error,
        Long totalInputTokens,
        Long totalOutputTokens,
        String totalCostUsd
    ) {
    }
}
