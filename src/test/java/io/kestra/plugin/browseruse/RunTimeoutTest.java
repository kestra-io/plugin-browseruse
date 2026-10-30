package io.kestra.plugin.browseruse;

import java.net.URL;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.WireMockServer;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.flows.State;
import io.kestra.core.queues.QueueException;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.TestRunnerUtils;
import io.kestra.core.utils.TestsUtils;

import jakarta.inject.Inject;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

@KestraTest(startRunner = true)
class RunTimeoutTest {
    private static final String RUN_ID = "11111111-1111-1111-1111-111111111111";
    private static final String API_KEY = "bu_test";

    @Inject
    private TestRunnerUtils runnerUtils;

    @Inject
    private LocalFlowRepositoryLoader repositoryLoader;

    private WireMockServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
        server.stubFor(post(urlEqualTo("/api/v4/runs"))
            .willReturn(okJson("""
                {"id":"%s","status":"queued"}
                """.formatted(RUN_ID))));
        server.stubFor(get(urlPathMatching("/api/v4/runs/.*/status"))
            .willReturn(okJson("{\"status\":\"running\"}")));
        server.stubFor(post(urlPathMatching("/api/v4/runs/.*/cancel"))
            .willReturn(okJson("""
                {"id":"%s","status":"cancelled"}
                """.formatted(RUN_ID))));

        URL flow = RunTimeoutTest.class.getClassLoader().getResource("flows/browseruse_timeout.yaml");
        TestsUtils.loads("main", repositoryLoader, flow);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void timeoutCancelsTheRun() throws TimeoutException, QueueException, InterruptedException {
        var execution = runnerUtils.runOne(
            "main",
            "company.team",
            "browseruse_timeout",
            null,
            (flow, ignored) -> Map.of("baseUrl", "http://localhost:" + server.port() + "/api/v4")
        );

        assertThat(execution.getState().getCurrent(), is(State.Type.FAILED));
        assertThat(execution.getTaskRunList(), hasSize(1));
        assertThat(execution.getTaskRunList().getFirst().getState().getCurrent(), is(State.Type.FAILED));

        var deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline && server.findAll(postRequestedFor(urlPathMatching("/api/v4/runs/.*/cancel"))).isEmpty()) {
            Thread.sleep(20);
        }
        assertThat(server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))), hasSize(1));
        assertThat(server.findAll(postRequestedFor(urlPathMatching("/api/v4/runs/.*/cancel"))), hasSize(1));
        assertThat(
            server.findAll(postRequestedFor(urlEqualTo("/api/v4/runs"))).getFirst().getHeader("X-Browser-Use-API-Key"),
            is(API_KEY)
        );
    }
}
