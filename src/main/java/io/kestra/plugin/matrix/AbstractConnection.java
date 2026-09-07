package io.kestra.plugin.matrix;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.http.client.configurations.TimeoutConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractConnection extends Task implements RunnableTask<VoidOutput> {
    @Schema(
        title = "Configure Matrix HTTP client",
        description = """
            Optional HTTP client overrides for Matrix calls.
            Leave unset to use the defaults: no connect timeout, a 5m read idle timeout and UTF-8 as the request charset."""
    )
    @PluginProperty(group = "advanced")
    protected RequestOptions options;

    protected HttpConfiguration httpClientConfigurationWithOptions() throws IllegalVariableEvaluationException {
        HttpConfiguration.HttpConfigurationBuilder configuration = HttpConfiguration.builder();

        if (this.options != null) {
            configuration
                .timeout(
                    TimeoutConfiguration.builder()
                        .connectTimeout(this.options.getConnectTimeout())
                        .readIdleTimeout(this.options.getReadIdleTimeout())
                        .build()
                )
                .defaultCharset(this.options.getDefaultCharset());
        }

        return configuration.build();
    }

    protected HttpRequest.HttpRequestBuilder createRequestBuilder(RunContext runContext) throws IllegalVariableEvaluationException {
        HttpRequest.HttpRequestBuilder builder = HttpRequest.builder();

        if (this.options != null && this.options.getHeaders() != null) {
            Map<String, String> headers = runContext.render(this.options.getHeaders())
                .asMap(String.class, String.class);

            if (headers != null) {
                headers.forEach(builder::addHeader);
            }
        }
        return builder;
    }

    /**
     * Only the options that Kestra's {@link HttpConfiguration} still supports are exposed here.
     * The legacy {@code readTimeout}, {@code connectionPoolIdleTimeout} and {@code maxContentLength}
     * builder setters are deprecated in Kestra 1.x and removed altogether in 2.x, so declaring them
     * would either be silently ignored or fail at runtime on a 2.x instance.
     */
    @Getter
    @Builder
    public static class RequestOptions {
        @Schema(
            title = "Connect timeout",
            description = """
                Maximum time to establish the connection before failing; unset uses the client default."""
        )
        @PluginProperty(group = "execution")
        private final Property<Duration> connectTimeout;

        @Schema(
            title = "Read idle timeout",
            description = """
                Idle time allowed while reading before the connection is closed; defaults to 5m."""
        )
        @Builder.Default
        @PluginProperty(group = "execution")
        private final Property<Duration> readIdleTimeout = Property.ofValue(Duration.of(5, ChronoUnit.MINUTES));

        @Schema(
            title = "Request charset",
            description = """
                Charset used to encode the request body; defaults to UTF-8."""
        )
        @Builder.Default
        @PluginProperty(group = "advanced")
        private final Property<Charset> defaultCharset = Property.ofValue(StandardCharsets.UTF_8);

        @Schema(
            title = "HTTP headers",
            description = """
                Additional HTTP headers to include in every request sent to the homeserver."""
        )
        @PluginProperty(group = "advanced")
        public Property<Map<String, String>> headers;
    }
}
