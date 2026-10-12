package io.kestra.plugin.browseruse;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.kestra.core.exceptions.KilledException;
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
    static final int CANCEL_ATTEMPTS = 3;

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
     * A 503 is retried twice, and {@code Retry-After} is read as a number of seconds.
     */
    void cancel(String runId) throws Exception {
        var label = "POST /runs/" + runId + "/cancel";
        var attempt = 1;
        while (true) {
            var result = execute(request("POST", runPath(runId) + "/cancel", null));
            if (result.code() == 404 || result.code() < 300) {
                return;
            }
            if (result.code() != 503 || attempt >= CANCEL_ATTEMPTS) {
                throw httpFailure(label, result.code(), result.body(), false);
            }
            var wait = retryDelay(result);
            logger.warn(
                "Browser Use {} returned 503. Waiting {} before retry {} of {}.",
                label,
                wait,
                attempt,
                CANCEL_ATTEMPTS
            );
            pause(wait, cancelled);
            attempt++;
        }
    }

    private RunPayload send(String method, String path, Map<String, Object> body, String label, boolean createCall) throws Exception {
        var attempt = 1;
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new KilledException("Task was killed");
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
                pause(DEFAULT_RETRY_DELAY, cancelled);
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
                pause(DEFAULT_RETRY_DELAY, cancelled);
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
                pause(wait, cancelled);
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
            var parsed = seconds(result.retryAfter().get());
            if (parsed != null) {
                return cap(parsed);
            }
        }
        var body = read(result.body(), ErrorBody.class);
        if (body != null && body.retryAfterSeconds() != null) {
            return cap(Duration.ofSeconds(Math.max(0, body.retryAfterSeconds())));
        }
        return DEFAULT_RETRY_DELAY;
    }

    private static Duration seconds(String header) {
        try {
            return Duration.ofSeconds(Math.max(0, Long.parseLong(header.trim())));
        } catch (NumberFormatException e) {
            return null;
        }
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

    static void pause(Duration delay, BooleanSupplier stop) {
        var end = System.nanoTime() + delay.toNanos();
        while (System.nanoTime() < end) {
            if (stop.getAsBoolean()) {
                return;
            }
            var remainingMs = (end - System.nanoTime()) / 1_000_000L;
            if (remainingMs <= 0) {
                return;
            }
            try {
                Thread.sleep(Math.min(200L, remainingMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KilledException("Task was killed");
            }
        }
    }

    private static RunPayload parse(String body, String label) {
        try {
            return JacksonMapper.ofJson().readValue(body, RunPayload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                "Browser Use returned a response for " + label + " that is not valid JSON. " + snippet(body),
                e
            );
        }
    }

    private static String runPath(String runId) {
        return "/runs/" + URLEncoder.encode(runId, StandardCharsets.UTF_8).replace("+", "%20");
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
            case 503 -> "The service is temporarily unavailable.";
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
        var parsed = read(body, ErrorBody.class);
        if (parsed == null) {
            return snippet(body);
        }
        if (parsed.detail() instanceof String text && !text.isBlank()) {
            return text;
        }
        if (parsed.detail() instanceof List<?> items) {
            var messages = new StringBuilder();
            for (var item : items) {
                if (item instanceof Map<?, ?> map && map.get("msg") instanceof String msg && !msg.isBlank()) {
                    if (!messages.isEmpty()) {
                        messages.append("; ");
                    }
                    messages.append(msg);
                }
            }
            if (!messages.isEmpty()) {
                return messages.toString();
            }
        }
        if (parsed.error() != null && !parsed.error().isBlank()) {
            return parsed.error();
        }
        return snippet(body);
    }

    private static <T> T read(String body, Class<T> type) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return JacksonMapper.ofJson().readValue(body, type);
        } catch (JsonProcessingException e) {
            return null;
        }
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunPayload(
        String id,
        String status,
        String result,
        String error,
        Long totalInputTokens,
        Long totalOutputTokens,
        BigDecimal totalCostUsd
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ErrorBody(
        Object detail,
        String error,
        @JsonProperty("retry_after_seconds") Long retryAfterSeconds
    ) {
    }
}
