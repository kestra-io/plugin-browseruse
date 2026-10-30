package io.kestra.plugin.browseruse;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.exceptions.KilledException;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Metric;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import org.slf4j.Logger;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Run a Browser Use cloud agent",
    description = """
        Gives a [Browser Use](https://browser-use.com) cloud agent a task in plain language, waits until the run finishes, and returns its answer. \
        The task creates a run, polls `GET /runs/{id}/status` until the run is completed, failed, or cancelled, then reads the full run once. \
        A failed or cancelled run fails the task. If the task fails after the run is created, it cancels that run so the agent does not keep spending credits. \
        The task returns the agent's `result` text. Ask for JSON in the prompt and parse it with `fromJson`. \
        The task polls until the run ends, so set a task `timeout` or `maxCostUsd` to bound the time and the spend."""
)
@Plugin(
    examples = {
        @Example(
            title = "Check a price and parse the JSON answer",
            full = true,
            code = """
                id: competitor_price_check
                namespace: company.team

                tasks:
                  - id: check_price
                    type: io.kestra.plugin.browseruse.Run
                    apiKey: "{{ secret('BROWSER_USE_API_KEY') }}"
                    prompt: >
                      Open https://example.com/pricing, find the monthly price of the Pro plan
                      and reply with only JSON like {"plan": "Pro", "price_eur": 49}.
                    maxCostUsd: 1

                  - id: log_price
                    type: io.kestra.plugin.core.log.Log
                    message: "Pro plan costs {{ fromJson(outputs.check_price.result).price_eur }} EUR"
                """
        ),
        @Example(
            title = "Choose the model and the poll interval",
            full = true,
            code = """
                id: browser_use_with_model
                namespace: company.team

                tasks:
                  - id: check
                    type: io.kestra.plugin.browseruse.Run
                    apiKey: "{{ secret('BROWSER_USE_API_KEY') }}"
                    model: gpt-5.6-luna
                    pollInterval: PT15S
                    prompt: Find the top story on https://news.ycombinator.com and reply with the title only.
                """
        )
    },
    metrics = {
        @Metric(
            name = "input.token.count",
            type = Counter.TYPE,
            unit = "token",
            description = "Input tokens reported by the Browser Use run"
        ),
        @Metric(
            name = "output.token.count",
            type = Counter.TYPE,
            unit = "token",
            description = "Output tokens reported by the Browser Use run"
        ),
        @Metric(
            name = "total.token.count",
            type = Counter.TYPE,
            unit = "token",
            description = "Input plus output tokens reported by the Browser Use run"
        )
    }
)
public class Run extends Task implements RunnableTask<Run.Output> {
    static final String DEFAULT_BASE_URL = "https://api.browser-use.com/api/v4";
    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    static final Duration MIN_POLL_INTERVAL = Duration.ofSeconds(1);
    static final Duration MAX_POLL_INTERVAL = Duration.ofMinutes(5);

    @Schema(
        title = "Browser Use API key",
        description = "API key sent in the `X-Browser-Use-API-Key` header. Create one at https://cloud.browser-use.com/settings?tab=api-keys."
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    private Property<String> apiKey;

    @Schema(
        title = "API base URL",
        description = "Browser Use API v4 base URL. Defaults to `https://api.browser-use.com/api/v4`."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    private Property<String> baseUrl = Property.ofValue(DEFAULT_BASE_URL);

    @Schema(
        title = "Instructions for the browser agent",
        description = """
            What the agent should do, in plain language. The value is sent as the API `task` field. \
            Ask for JSON in the text if a later task needs structured fields."""
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> prompt;

    @Schema(
        title = "Model",
        description = """
            Optional model id, for example `gpt-6-luna`. Leave empty to use Browser Use's current default. \
            The model list changes, so this is a free string rather than a fixed set."""
    )
    @PluginProperty(group = "main")
    private Property<String> model;

    @Schema(
        title = "Maximum cost in USD",
        description = "Optional cap sent as `maxCostUsd`. Browser Use stops the run when the cap is reached. Must be greater than 0."
    )
    @PluginProperty(group = "reliability")
    private Property<@Positive BigDecimal> maxCostUsd;

    @Schema(
        title = "Status poll interval",
        description = """
            How long to wait between status reads. Defaults to 2 seconds. Minimum is 1 second and maximum is 5 minutes. \
            Use an ISO-8601 duration, for example `PT2S`."""
    )
    @Builder.Default
    @PluginProperty(group = "execution")
    private Property<Duration> pollInterval = Property.ofValue(DEFAULT_POLL_INTERVAL);

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private final AtomicReference<Runnable> killable = new AtomicReference<>();

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private final AtomicBoolean isKilled = new AtomicBoolean(false);

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Builder.Default
    private final AtomicBoolean cancelDispatched = new AtomicBoolean(false);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var taskLogger = runContext.logger();
        var config = renderConfig(runContext);
        // A task retry reuses this instance: forget the previous attempt's run.
        killable.set(null);
        cancelDispatched.set(false);
        if (isKilled.get()) {
            throw new KilledException("Task was killed");
        }

        try (var client = BrowserUseClient.open(
            runContext,
            config.baseUrl(),
            config.apiKey(),
            taskLogger,
            isKilled::get,
            Duration.ofSeconds(10),
            Duration.ofSeconds(30)
        )) {
            String createdRunId = null;
            var runReachedTerminal = false;
            try {
                var created = client.createRun(config.prompt(), config.model(), config.maxCostUsd());
                var id = created.id();
                if (id == null || id.isBlank()) {
                    throw new IllegalStateException(
                        "Browser Use accepted the create call but returned no run id. Check the dashboard before starting another run."
                    );
                }
                final var runId = id;
                createdRunId = runId;
                taskLogger.info("Created Browser Use run {}.", runId);

                killable.set(() -> cancelRun(runContext, taskLogger, config.baseUrl(), config.apiKey(), runId));
                // A kill before the run id existed had nothing to cancel.
                if (isKilled.get()) {
                    throw killed(runId);
                }

                var status = poll(client, taskLogger, runId, config.pollInterval());
                if (isKilled.get()) {
                    throw killed(runId);
                }

                var summary = client.readRun(runId);
                if (isKilled.get() && (summary.status() == null || !"completed".equalsIgnoreCase(summary.status()))) {
                    throw killed(runId);
                }
                if (summary.status() == null || !isTerminal(summary.status())) {
                    throw new IllegalStateException(
                        "Browser Use run " + runId + " reported status " + status
                            + " but the full run is " + summary.status()
                            + ". Check the run in the Browser Use dashboard before starting another one."
                    );
                }
                runReachedTerminal = true;
                emitMetrics(runContext, summary);
                if ("completed".equalsIgnoreCase(summary.status())) {
                    taskLogger.info("Browser Use run {} completed.", runId);
                    return toOutput(runId, summary);
                }
                throw terminalFailure(runId, summary);
            } catch (Exception e) {
                // Cancel the billed run so a task retry does not start a second one.
                if (createdRunId != null && !runReachedTerminal) {
                    if (e instanceof BrowserUseClient.PauseInterrupted && !isKilled.get()) {
                        cancelOnTaskThread();
                    } else {
                        dispatchRemoteCancel();
                    }
                }
                if (e instanceof BrowserUseClient.PauseInterrupted) {
                    throw failureFromInterrupt(createdRunId);
                }
                throw e;
            }
        }
    }

    private String poll(BrowserUseClient client, Logger logger, String runId, Duration pollInterval) throws Exception {
        String lastStatus = null;
        while (true) {
            if (isKilled.get()) {
                return lastStatus;
            }
            var status = client.readStatus(runId);
            if (!status.equals(lastStatus)) {
                logger.info("Browser Use run {} is {}.", runId, status);
                lastStatus = status;
            } else {
                logger.debug("Browser Use run {} is still {}.", runId, status);
            }
            if (isTerminal(status)) {
                return status;
            }
            BrowserUseClient.pause(pollInterval, isKilled::get);
        }
    }

    private Config renderConfig(RunContext runContext) throws Exception {
        var rApiKey = requireText(
            runContext.render(apiKey).as(String.class).orElse(null),
            "apiKey",
            "Set apiKey to a Browser Use API key, for example {{ secret('BROWSER_USE_API_KEY') }}."
        );
        var rPrompt = requireText(
            runContext.render(prompt).as(String.class).orElse(null),
            "prompt",
            "Set prompt to the instruction the browser agent should carry out."
        );
        var rModel = runContext.render(model).as(String.class).filter(value -> !value.isBlank()).map(String::trim).orElse(null);
        var rMaxCost = runContext.render(maxCostUsd).as(BigDecimal.class).orElse(null);
        if (rMaxCost != null && rMaxCost.signum() <= 0) {
            throw new IllegalArgumentException("maxCostUsd must be greater than 0. Got " + rMaxCost + ".");
        }
        var rPoll = runContext.render(pollInterval).as(Duration.class).orElse(DEFAULT_POLL_INTERVAL);
        if (rPoll.compareTo(MIN_POLL_INTERVAL) < 0 || rPoll.compareTo(MAX_POLL_INTERVAL) > 0) {
            throw new IllegalArgumentException(
                "pollInterval must be between PT1S and PT5M. Got " + rPoll + "."
            );
        }
        var rBaseUrl = normalizeBaseUrl(runContext.render(baseUrl).as(String.class).orElse(DEFAULT_BASE_URL));
        return new Config(rApiKey, rPrompt, rModel, rMaxCost, rPoll, rBaseUrl);
    }

    private static String requireText(String value, String name, String hint) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required. " + hint);
        }
        return value.trim();
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        var trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        final URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("baseUrl must be an absolute http or https URL. Got '" + value + "'.", e);
        }
        var scheme = uri.getScheme();
        if (uri.getHost() == null || scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("baseUrl must be an absolute http or https URL. Got '" + value + "'.");
        }
        return trimmed;
    }

    private static boolean isTerminal(String status) {
        if (status == null) {
            return false;
        }
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "completed", "failed", "cancelled" -> true;
            default -> false;
        };
    }

    private void emitMetrics(RunContext runContext, BrowserUseClient.RunPayload run) {
        if (run.totalInputTokens() != null) {
            runContext.metric(Counter.of("input.token.count", run.totalInputTokens()));
        }
        if (run.totalOutputTokens() != null) {
            runContext.metric(Counter.of("output.token.count", run.totalOutputTokens()));
        }
        if (run.totalInputTokens() != null && run.totalOutputTokens() != null) {
            runContext.metric(Counter.of("total.token.count", run.totalInputTokens() + run.totalOutputTokens()));
        }
    }

    private static Output toOutput(String runId, BrowserUseClient.RunPayload summary) {
        return Output.builder()
            .runId(runId)
            .status(summary.status())
            .result(summary.result())
            .totalInputTokens(summary.totalInputTokens())
            .totalOutputTokens(summary.totalOutputTokens())
            .totalCostUsd(summary.totalCostUsd())
            .build();
    }

    private static IllegalStateException terminalFailure(String runId, BrowserUseClient.RunPayload summary) {
        var reason = summary.error() == null || summary.error().isBlank()
            ? "Browser Use did not include an error message."
            : summary.error();
        if ("cancelled".equalsIgnoreCase(summary.status())) {
            return new IllegalStateException(
                "Browser Use run " + runId + " was cancelled. " + reason
                    + " If this was not expected, open the run in the Browser Use dashboard before starting another one."
            );
        }
        return new IllegalStateException(
            "Browser Use run " + runId + " failed: " + reason
                + " Open the run in the Browser Use dashboard, adjust the prompt, or raise maxCostUsd if the run stopped on cost."
        );
    }

    private static KilledException killed(String runId) {
        return new KilledException(
            "Browser Use run " + runId + " was cancelled because the task was killed or timed out. "
                + "If it is still active in the Browser Use dashboard, cancel it there so it does not keep spending credits."
        );
    }

    private RuntimeException failureFromInterrupt(String runId) {
        if (isKilled.get()) {
            return runId == null ? new KilledException("Task was killed") : killed(runId);
        }
        if (runId == null) {
            return new IllegalStateException("The worker shut down before the Browser Use run was created.");
        }
        return new IllegalStateException(
            "Browser Use run " + runId + " was cancelled because the worker shut down before the task finished."
        );
    }

    // kill() runs on the worker thread, so the HTTP cancel cannot run inline.
    private void dispatchRemoteCancel() {
        var remoteCancel = killable.get();
        // Leave the flag unset until a run id exists, or the later dispatch is skipped.
        if (remoteCancel == null || !cancelDispatched.compareAndSet(false, true)) {
            return;
        }
        try {
            Thread.ofVirtual().name("browser-use-run-cancel").start(remoteCancel);
        } catch (Exception ignored) {
            // kill() must not throw when the cancel thread cannot start.
        }
    }

    // The worker is exiting, so this cancel has to finish before that thread returns.
    private void cancelOnTaskThread() {
        var remoteCancel = killable.get();
        if (remoteCancel == null || !cancelDispatched.compareAndSet(false, true)) {
            return;
        }
        var interrupted = Thread.interrupted();
        try {
            remoteCancel.run();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void cancelRun(RunContext runContext, Logger taskLogger, String baseUrl, String apiKey, String runId) {
        try (var client = BrowserUseClient.open(
            runContext,
            baseUrl,
            apiKey,
            taskLogger,
            () -> false,
            Duration.ofSeconds(5),
            Duration.ofSeconds(10)
        )) {
            client.cancel(runId);
            taskLogger.info("Cancelled Browser Use run {}.", runId);
        } catch (Exception e) {
            taskLogger.warn("Failed to cancel Browser Use run {}: {}", runId, e.getMessage());
        }
    }

    // stop() stays a no-op so worker shutdown can finish the run during the grace period.
    @Override
    public void kill() {
        if (!isKilled.compareAndSet(false, true)) {
            return;
        }
        dispatchRemoteCancel();
    }

    private record Config(
        String apiKey,
        String prompt,
        String model,
        BigDecimal maxCostUsd,
        Duration pollInterval,
        String baseUrl
    ) {
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {
        @Schema(
            title = "Run ID",
            description = "Identifier of the Browser Use run"
        )
        private final String runId;

        @Schema(
            title = "Run status",
            description = "Terminal status reported by Browser Use. A successful task returns `completed`."
        )
        private final String status;

        @Schema(
            title = "Agent result",
            description = "Final answer from the agent, as text. Ask for JSON in the prompt and parse it with `fromJson`."
        )
        private final String result;

        @Schema(
            title = "Input tokens",
            description = "Input tokens reported for the run"
        )
        private final Long totalInputTokens;

        @Schema(
            title = "Output tokens",
            description = "Output tokens reported for the run"
        )
        private final Long totalOutputTokens;

        @Schema(
            title = "Total cost in USD",
            description = "Total cost reported for the run"
        )
        private final BigDecimal totalCostUsd;
    }
}
