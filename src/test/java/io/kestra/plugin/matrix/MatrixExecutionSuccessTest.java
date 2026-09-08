package io.kestra.plugin.matrix;

import java.io.IOException;
import java.net.URISyntaxException;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.TestRunner;

import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.comparesEqualTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * Covers the template's non-failure branch plus the customMessage/customFields overrides, which the
 * failing-execution scenario in {@link MatrixExecutionTest} never exercises. It lives in its own class
 * because each Flow-trigger scenario needs its own execution-queue subscription.
 */
@KestraTest
class MatrixExecutionSuccessTest extends AbstractMatrixTest {
    @Inject
    protected TestRunner runner;
    @Inject
    protected LocalFlowRepositoryLoader repositoryLoader;

    @BeforeAll
    void setUpFlows() throws IOException, URISyntaxException {
        repositoryLoader.load(flowsWithEmbeddedServerUrl());
        this.runner.run();
    }

    @BeforeEach
    protected void init() {
        resetFakeController();
    }

    @Test
    void flowThatSucceeds() throws Exception {
        runAndCaptureExecution(
            "main-flow-that-succeeds",
            "matrix-success"
        );

        awaitMessage();

        String body = FakeMatrixController.message.body();

        assertThat(FakeMatrixController.message.msgtype(), comparesEqualTo("m.notice"));
        assertThat(body, containsString("io.kestra.tests/main-flow-that-succeeds"));
        assertThat(body, containsString("SUCCESS"));
        // the firstFailed == false branch must not emit a failing-task fragment
        assertThat(body, not(containsString("Failing task")));
        assertThat(body, containsString("nightly batch finished"));
        assertThat(body, containsString("env: qa"));
    }
}
