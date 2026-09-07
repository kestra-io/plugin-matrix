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

@KestraTest
class MatrixExecutionTest extends AbstractMatrixTest {
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
    void flow() throws Exception {
        runAndCaptureExecution(
            "main-flow-that-fails",
            "matrix"
        );

        awaitMessage();

        assertThat(FakeMatrixController.authorizationHeader, comparesEqualTo("Bearer token"));
        assertThat(FakeMatrixController.roomId, comparesEqualTo("!room:example.org"));
        assertThat(FakeMatrixController.message.body(), containsString("io.kestra.tests/main-flow-that-fails"));
        assertThat(FakeMatrixController.message.body(), containsString("Failing task: failed"));
    }
}
