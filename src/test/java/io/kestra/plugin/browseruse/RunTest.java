package io.kestra.plugin.browseruse;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.executions.metrics.Counter;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.core.utils.IdUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@KestraTest
class RunTest {
    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";
    private static final String API_KEY = "bu_test";

    @Inject
    private RunContextFactory runContextFactory;

    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void shouldReturnTheResultAfterPolling() throws Exception {
        stubCreate();
        stubRunningThenCompleted();
        stubRun("completed", "{\"price_eur\":49}", null, 120, 30, "0.012300");

        var task = task(Property.ofValue("find the pro price"));
        var runContext = runContextFactory.of();
        var output = task.run(runContext);

        assertThat(output.getRunId(), is(RUN_ID));
        assertThat(output.getStatus(), is("completed"));
        assertThat(output.getResult(), is("{\"price_eur\":49}"));
        assertThat(output.getError(), nullValue());
        assertThat(output.getTotalInputTokens(), is(120L));
        assertThat(output.getTotalOutputTokens(), is(30L));
        assertThat(output.getTotalCostUsd(), is("0.012300"));
        assertThat(metric(runContext, "input.token.count"), is(120d));
        assertThat(metric(runContext, "output.token.count"), is(30d));
        assertThat(metric(runContext, "total.token.count"), is(150d));
        assertThat(metric(runContext, "cost.usd"), closeTo(0.0123d, 0.0000001d));

        var body = createBody();
        assertThat(body.get("task").asText(), is("find the pro price"));
        assertThat(body.has("model"), is(false));
        assertThat(body.has("maxCostUsd"), is(false));
        assertThat(body.has("outputSchema"), is(false));
        assertThat(server.findAll(getRequestedFor(urlEqualTo("/api/v4/runs/" + RUN_ID + "/status"))), hasSize(2));
        assertThat(server.findAll(postRequestedFor(urlPathMatching(".*/cancel"))), empty());
    }

    @Test
    void shouldRenderThePromptAndSendOptionalFields() throws Exception {
        stubCreate();
        stubStatus("completed");
        server.stubFor(get(urlEqualTo("/api/v4/runs/" + RUN_ID))
            .willReturn(okJson("""
                {
                  "id": "%s",
                  "status": "completed",
                  "result": "done",
                  "totalInputTokens": 1,
                  "totalOutputTokens": 2,
                  "totalCostUsd": 0.5
                }
                """.formatted(RUN_ID))));

        var task = Run.builder()
            .id(taskId())
            .type(Run.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(baseUrl()))
            .prompt(Property.ofExpression("{{ inputs.question }}"))
            .model(Property.ofValue("gpt-6-luna"))
            .maxCostUsd(Property.ofValue(new BigDecimal("1.50")))
            .build();
        var runContext = TestsUtils.mockRunContext(runContextFactory, task, Map.of("question", "find the pro price"));

        var output = task.run(runContext);

        assertThat(output.getResult(), is("done"));
        assertThat(output.getTotalCostUsd(), is("0.5"));
        assertThat(metric(runContext, "cost.usd"), closeTo(0.5d, 0.0000001d));
        var body = createBody();
        assertThat(body.get("task").asText(), is("find the pro price"));
        assertThat(body.get("model").asText(), is("gpt-6-luna"));
        assertThat(new BigDecimal(body.get("maxCostUsd").asText()).compareTo(new BigDecimal("1.50")), is(0));
    }

    @Test
    void shouldFailWhenTheRunFailsAndStillRecordUsage() throws Exception {
        stubCreate();
        stubStatus("failed");
        stubRun("failed", null, "could not open the pricing page", 4, 1, "0.100000");

        var runContext = runContextFactory.of();
        var error = assertThrows(IllegalStateException.class, () -> task(Property.ofValue("find the price")).run(runContext));

        assertThat(error.getMessage(), containsString(RUN_ID));
        assertThat(error.getMessage(), containsString("could not open the pricing page"));
        assertThat(metric(runContext, "input.token.count"), is(4d));
        assertThat(metric(runContext, "cost.usd"), closeTo(0.1d, 0.0000001d));
    }

    @Test
    void shouldFailWhenTheRunIsCancelled() throws Exception {
        stubCreate();
        stubStatus("cancelled");
        stubRun("cancelled", null, "stopped by the user", 2, 0, "0.010000");

        var error = assertThrows(
            IllegalStateException.class,
            () -> task(Property.ofValue("find the price")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString("was cancelled"));
        assertThat(error.getMessage(), containsString("stopped by the user"));
    }

    @Test
    void shouldRetryCreateWhenRateLimited() throws Exception {
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .inScenario("limited")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "1").withBody("{\"detail\":\"slow down\"}"))
            .willSetStateTo("open"));
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .inScenario("limited")
            .whenScenarioStateIs("open")
            .willReturn(okJson(createdBody())));
        stubStatus("completed");
        stubRun("completed", "ok", null, 1, 1, "0.000001");

        var started = System.nanoTime();
        var output = task(Property.ofValue("find the price")).run(runContextFactory.of());
        var elapsedMs = Duration.ofNanos(System.nanoTime() - started).toMillis();

        assertThat(output.getResult(), is("ok"));
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))), hasSize(2));
        assertTrue(elapsedMs >= 700, "Retry-After of 1 second was not honored, elapsed " + elapsedMs + "ms");
    }

    @Test
    void shouldStopAfterRepeatedRateLimits() {
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0").withBody("{\"retry_after_seconds\":0}")));

        var error = assertThrows(
            IllegalStateException.class,
            () -> task(Property.ofValue("find the price")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString("429"));
        assertThat(error.getMessage(), containsString("concurrency"));
        // The Kestra HTTP client also retries 429 once before returning, so the post count is between one and two per attempt.
        var posts = server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))).size();
        assertThat(posts, allOf(greaterThanOrEqualTo(BrowserUseClient.MAX_ATTEMPTS), lessThanOrEqualTo(BrowserUseClient.MAX_ATTEMPTS * 2)));
    }

    @Test
    void shouldNotRetryCreateAfterATransportError() {
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var error = assertThrows(
            IllegalStateException.class,
            () -> task(Property.ofValue("find the price")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString("not retried"));
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))), hasSize(1));
    }

    @Test
    void shouldNotRetryCreateAfterAServerError() {
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .willReturn(aResponse().withStatus(500).withBody("{\"detail\":\"boom\"}")));

        var error = assertThrows(
            IllegalStateException.class,
            () -> task(Property.ofValue("find the price")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString("500"));
        assertThat(error.getMessage(), containsString("boom"));
        assertThat(error.getMessage(), containsString("may already exist"));
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))), hasSize(1));
    }

    @Test
    void shouldExplainAnUnauthorizedKey() {
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .willReturn(aResponse().withStatus(401).withBody("{\"detail\":\"invalid api key\"}")));

        var error = assertThrows(
            IllegalStateException.class,
            () -> task(Property.ofValue("find the price")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString("401"));
        assertThat(error.getMessage(), containsString("API key"));
        assertThat(error.getMessage(), not(containsString(API_KEY)));
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))), hasSize(1));
    }

    @Test
    void shouldNotRetryAFailedStatusRead() throws Exception {
        stubCreate();
        server.stubFor(get(urlEqualTo("/api/v4/runs/" + RUN_ID + "/status"))
            .willReturn(aResponse().withStatus(500).withBody("{\"detail\":\"status unavailable\"}")));

        var error = assertThrows(
            IllegalStateException.class,
            () -> task(Property.ofValue("find the price")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString(RUN_ID));
        assertThat(error.getMessage(), containsString("500"));
        assertThat(server.findAll(getRequestedFor(urlEqualTo("/api/v4/runs/" + RUN_ID + "/status"))), hasSize(1));
    }

    @Test
    void shouldCancelTheRunWhenKilled() throws Exception {
        assertCancellation(Run::kill);
    }

    @Test
    void shouldCancelTheRunWhenStopped() throws Exception {
        assertCancellation(Run::stop);
    }

    @Test
    void shouldRejectABlankPromptBeforeCallingTheApi() {
        var error = assertThrows(
            IllegalArgumentException.class,
            () -> task(Property.ofValue("  ")).run(runContextFactory.of())
        );

        assertThat(error.getMessage(), containsString("prompt"));
        assertThat(server.getAllServeEvents(), empty());
    }

    @Test
    void shouldRejectAMissingApiKeyBeforeCallingTheApi() {
        var task = Run.builder()
            .id(taskId())
            .type(Run.class.getName())
            .baseUrl(Property.ofValue(baseUrl()))
            .prompt(Property.ofValue("find the price"))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(error.getMessage(), containsString("apiKey"));
        assertThat(server.getAllServeEvents(), empty());
    }

    @Test
    void shouldRejectACostCapThatIsNotPositive() {
        var task = Run.builder()
            .id(taskId())
            .type(Run.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(baseUrl()))
            .prompt(Property.ofValue("find the price"))
            .maxCostUsd(Property.ofValue(BigDecimal.ZERO))
            .build();

        var error = assertThrows(IllegalArgumentException.class, () -> task.run(runContextFactory.of()));

        assertThat(error.getMessage(), containsString("maxCostUsd"));
        assertThat(server.getAllServeEvents(), empty());
    }

    @Test
    void shouldRejectAPollIntervalOutsideTheBounds() {
        var tooFast = Run.builder()
            .id(taskId())
            .type(Run.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(baseUrl()))
            .prompt(Property.ofValue("find the price"))
            .pollInterval(Property.ofValue(Duration.ofMillis(500)))
            .build();
        var tooSlow = Run.builder()
            .id(taskId())
            .type(Run.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(baseUrl()))
            .prompt(Property.ofValue("find the price"))
            .pollInterval(Property.ofValue(Duration.ofMinutes(10)))
            .build();

        assertThat(
            assertThrows(IllegalArgumentException.class, () -> tooFast.run(runContextFactory.of())).getMessage(),
            containsString("pollInterval")
        );
        assertThat(
            assertThrows(IllegalArgumentException.class, () -> tooSlow.run(runContextFactory.of())).getMessage(),
            containsString("pollInterval")
        );
        assertThat(server.getAllServeEvents(), empty());
    }

    private void assertCancellation(Consumer<Run> signal) throws Exception {
        stubCreate();
        server.stubFor(get(urlPathMatching("/api/v4/runs/" + RUN_ID + "/status"))
            .willReturn(okJson("{\"status\":\"running\"}")));
        server.stubFor(post(urlPathMatching("/api/v4/runs/" + RUN_ID + "/cancel"))
            .willReturn(okJson("{\"id\":\"" + RUN_ID + "\",\"status\":\"cancelled\"}")));

        var task = task(Property.ofValue("find the price"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(() -> task.run(runContextFactory.of()));
            waitUntil(() -> !server.findAll(getRequestedFor(urlPathMatching("/api/v4/runs/.*/status"))).isEmpty());
            signal.accept(task);
            try {
                future.get(10, TimeUnit.SECONDS);
                throw new AssertionError("Run completed instead of being cancelled. Events: " + server.getAllServeEvents());
            } catch (TimeoutException e) {
                throw new AssertionError("Run did not stop after cancellation. Events: " + server.getAllServeEvents(), e);
            } catch (ExecutionException e) {
                assertThat(e.getCause(), instanceOf(InterruptedException.class));
                assertThat(e.getCause().getMessage(), containsString(RUN_ID));
                assertThat(e.getCause().getMessage(), containsString("cancelled"));
            }
            assertThat(server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))), hasSize(1));
            assertThat(server.findAll(postRequestedFor(urlPathMatching("/api/v4/runs/.*/cancel"))), hasSize(1));
        } finally {
            executor.shutdownNow();
        }
    }

    private void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out waiting for Browser Use traffic. Events: " + server.getAllServeEvents());
    }

    private Run task(Property<String> prompt) {
        return Run.builder()
            .id(taskId())
            .type(Run.class.getName())
            .apiKey(Property.ofValue(API_KEY))
            .baseUrl(Property.ofValue(baseUrl()))
            .prompt(prompt)
            .pollInterval(Property.ofValue(Duration.ofSeconds(1)))
            .build();
    }

    private String baseUrl() {
        return server.baseUrl() + "/api/v4/";
    }

    private static String taskId() {
        return "run-" + IdUtils.create();
    }

    private void stubCreate() {
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .withHeader("X-Browser-Use-API-Key", equalTo(API_KEY))
            .willReturn(okJson(createdBody())));
    }

    private void stubStatus(String status) {
        server.stubFor(get(urlEqualTo("/api/v4/runs/" + RUN_ID + "/status"))
            .withHeader("X-Browser-Use-API-Key", equalTo(API_KEY))
            .willReturn(okJson("{\"status\":\"" + status + "\"}")));
    }

    private void stubRunningThenCompleted() {
        server.stubFor(get(urlEqualTo("/api/v4/runs/" + RUN_ID + "/status"))
            .withHeader("X-Browser-Use-API-Key", equalTo(API_KEY))
            .inScenario("poll")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(okJson("{\"status\":\"running\"}"))
            .willSetStateTo("done"));
        server.stubFor(get(urlEqualTo("/api/v4/runs/" + RUN_ID + "/status"))
            .withHeader("X-Browser-Use-API-Key", equalTo(API_KEY))
            .inScenario("poll")
            .whenScenarioStateIs("done")
            .willReturn(okJson("{\"status\":\"completed\"}")));
    }

    private void stubRun(String status, String result, String error, int inputTokens, int outputTokens, String cost) throws Exception {
        server.stubFor(get(urlEqualTo("/api/v4/runs/" + RUN_ID))
            .withHeader("X-Browser-Use-API-Key", equalTo(API_KEY))
            .willReturn(okJson("""
                {
                  "id": "%s",
                  "status": "%s",
                  "result": %s,
                  "error": %s,
                  "totalInputTokens": %d,
                  "totalOutputTokens": %d,
                  "totalCostUsd": "%s",
                  "unexpected": "kept"
                }
                """.formatted(
                RUN_ID,
                status,
                JacksonMapper.ofJson().writeValueAsString(result),
                JacksonMapper.ofJson().writeValueAsString(error),
                inputTokens,
                outputTokens,
                cost
            ))));
    }

    private static String createdBody() {
        return """
            {
              "id": "%s",
              "status": "queued",
              "model": "gpt-6-luna",
              "sessionId": "22222222-2222-2222-2222-222222222222",
              "workspaceId": "33333333-3333-3333-3333-333333333333",
              "eventsUrl": "https://example.test/events",
              "brandNewField": {"ok": true}
            }
            """.formatted(RUN_ID);
    }

    private JsonNode createBody() throws Exception {
        var requests = server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs")));
        assertThat(requests, hasSize(1));
        assertThat(requests.getFirst().getHeader("X-Browser-Use-API-Key"), is(API_KEY));
        assertThat(requests.getFirst().getHeader("Authorization"), nullValue());
        return JacksonMapper.ofJson().readTree(requests.getFirst().getBodyAsString());
    }

    private static double metric(RunContext runContext, String name) {
        return runContext.metrics().stream()
            .filter(entry -> name.equals(entry.getName()))
            .map(entry -> {
                if (entry instanceof Counter counter) {
                    return counter.getValue();
                }
                throw new AssertionError("Unexpected metric type " + entry.getClass());
            })
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing metric " + name + " in " + runContext.metrics()));
    }
}
