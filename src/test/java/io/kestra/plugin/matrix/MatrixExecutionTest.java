package io.kestra.plugin.matrix;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.repositories.LocalFlowRepositoryLoader;
import io.kestra.core.runners.TestRunner;
import io.kestra.core.utils.Await;

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

    @BeforeEach
    protected void init() throws IOException, URISyntaxException {
        FakeMatrixController.message = null;
        FakeMatrixController.authorizationHeader = null;
        FakeMatrixController.roomId = null;
        FakeMatrixController.forcedErrorCode = null;

        repositoryLoader.load(Objects.requireNonNull(MatrixExecutionTest.class.getClassLoader().getResource("flows")));
        this.runner.run();
    }

    @Test
    void flow() throws Exception {
        runAndCaptureExecution(
            "main-flow-that-fails",
            "matrix"
        );

        try {
            Await.until(
                () -> FakeMatrixController.message,
                Duration.ofMillis(100),
                Duration.ofSeconds(5)
            );
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for FakeMatrixController.message to be set", e);
        }

        assertThat(FakeMatrixController.authorizationHeader, comparesEqualTo("Bearer token"));
        assertThat(FakeMatrixController.roomId, comparesEqualTo("!room:example.org"));
        assertThat(FakeMatrixController.message.body(), containsString("io.kestra.tests/main-flow-that-fails"));
        assertThat(FakeMatrixController.message.body(), containsString("Failing task: failed"));
    }
}
