package io.kestra.plugin.matrix;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;

import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.queues.QueueFactoryInterface;
import io.kestra.core.queues.QueueInterface;
import io.kestra.core.runners.TestRunnerUtils;
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
    void stopServer() {
        if (embeddedServer != null) {
            embeddedServer.stop();
        }
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
