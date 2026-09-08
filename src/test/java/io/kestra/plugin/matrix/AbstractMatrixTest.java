package io.kestra.plugin.matrix;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.runners.TestRunnerUtils;
import io.kestra.core.utils.Await;
import io.kestra.core.utils.TestsUtils;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import reactor.core.publisher.Flux;

import static io.kestra.core.tenant.TenantService.MAIN_TENANT;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@KestraTest
public class AbstractMatrixTest {
    @Inject
    protected EmbeddedServer embeddedServer;

    @Inject
    protected ApplicationContext applicationContext;

    @Inject
    @Named(QueueFactoryInterface.EXECUTION_NAMED)
    protected QueueInterface<io.kestra.core.models.executions.Execution> executionQueue;

    @Inject
    protected TestRunnerUtils runnerUtils;

    @BeforeAll
    void startServer() {
        embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();
    }

    @AfterAll
    void stopServer() throws IOException {
        if (embeddedServer != null) {
            embeddedServer.stop();
        }

        // @TempDir is not usable here: the directory is created from @BeforeAll in the subclasses,
        // before JUnit would inject a per-test temp dir.
        for (Path tempFlowDir : tempFlowDirs) {
            try (Stream<Path> paths = Files.walk(tempFlowDir)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        tempFlowDirs.clear();
    }

    /**
     * Placeholder written into the test flows in place of a hardcoded homeserver URL. The embedded
     * server binds a random port (see application.yml), so the real URL is only known at runtime and
     * is substituted into a temporary copy of the flows before they are loaded.
     */
    protected static final String URL_PLACEHOLDER = "MATRIX_TEST_URL";

    private final List<Path> tempFlowDirs = new ArrayList<>();

    protected void resetFakeController() {
        FakeMatrixController.message = null;
        FakeMatrixController.authorizationHeader = null;
        FakeMatrixController.roomId = null;
        FakeMatrixController.forcedErrorCode = null;
    }

    protected void awaitMessage() {
        try {
            Await.until(
                () -> FakeMatrixController.message,
                Duration.ofMillis(100),
                Duration.ofSeconds(5)
            );
        } catch (TimeoutException e) {
            throw new RuntimeException("Timed out waiting for FakeMatrixController.message to be set", e);
        }
    }

    /**
     * Copies the classpath {@code flows} directory to a temp directory, replacing
     * {@link #URL_PLACEHOLDER} with the embedded server's actual URL, and returns that directory.
     */
    protected URL flowsWithEmbeddedServerUrl() throws IOException, URISyntaxException {
        Path source = Path.of(Objects.requireNonNull(
            AbstractMatrixTest.class.getClassLoader().getResource("flows")
        ).toURI());
        Path target = Files.createTempDirectory("matrix-flows");
        tempFlowDirs.add(target);
        String serverUrl = embeddedServer.getURL().toString();

        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination);
                } else {
                    Files.createDirectories(destination.getParent());
                    Files.writeString(
                        destination,
                        Files.readString(path, StandardCharsets.UTF_8).replace(URL_PLACEHOLDER, serverUrl),
                        StandardCharsets.UTF_8
                    );
                }
            }
        }

        return target.toUri().toURL();
    }

    protected io.kestra.core.models.executions.Execution runAndCaptureExecution(String triggeringFlowId, String notificationFlowId) throws Exception {
        CountDownLatch queueCount = new CountDownLatch(1);
        AtomicReference<io.kestra.core.models.executions.Execution> last = new AtomicReference<>();

        Flux<io.kestra.core.models.executions.Execution> receive = TestsUtils.receive(executionQueue, execution -> {
            if (execution.getLeft().getFlowId().equals(notificationFlowId)) {
                last.set(execution.getLeft());
                queueCount.countDown();
            }
        });

        io.kestra.core.models.executions.Execution execution = runnerUtils.runOne(
            MAIN_TENANT,
            "io.kestra.tests",
            triggeringFlowId
        );

        boolean await = queueCount.await(20, TimeUnit.SECONDS);
        assertThat(await, is(true));

        io.kestra.core.models.executions.Execution triggeredExecution = last.get();
        assertThat(triggeredExecution, notNullValue());
        assertThat(triggeredExecution.getTrigger().getVariables().get("executionId"), is(execution.getId()));

        receive.blockLast();

        return execution;
    }
}
