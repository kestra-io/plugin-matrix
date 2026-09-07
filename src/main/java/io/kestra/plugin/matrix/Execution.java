package io.kestra.plugin.matrix;

import java.util.Map;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.plugins.notifications.ExecutionInterface;
import io.kestra.core.plugins.notifications.ExecutionService;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

// Note: this class name shadows io.kestra.core.models.executions.Execution within this file and
// package — reference the core type fully qualified wherever it is needed, never import it.
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Notify Matrix about execution result",
    description = "Sends a templated Matrix message with execution link, identifiers, timing, status, and failing task when applicable. Use with a [Flow trigger](https://kestra.io/docs/administrator-guide/monitoring#alerting); for `errors` handlers prefer [Send](https://kestra.io/plugins/plugin-matrix/io.kestra.plugin.matrix.send)."
)
@Plugin(
    examples = {
        @Example(
            title = "Send a Matrix notification on a failed flow execution.",
            full = true,
            code = """
                id: failure_alert
                namespace: company.team

                tasks:
                  - id: send_alert
                    type: io.kestra.plugin.matrix.Execution
                    homeserverUrl: "https://matrix.org"
                    accessToken: "{{ secret('MATRIX_ACCESS_TOKEN') }}"
                    roomId: "!OGEhHVWSdvArJzumhm:matrix.org"
                    executionId: "{{ trigger.executionId }}"

                triggers:
                  - id: failed_prod_workflows
                    type: io.kestra.plugin.core.trigger.Flow
                    states:
                      - FAILED
                      - WARNING
                    when: "{{ flow.namespace | startsWith('prod') }}"
                """
        )
    }
)
public class Execution extends Template implements ExecutionInterface {
    @Schema(
        title = "Execution ID",
        description = "ID of the execution to notify about; defaults to the current execution's ID."
    )
    @Builder.Default
    @PluginProperty(group = "advanced")
    private final Property<String> executionId = Property.ofExpression("{{ execution.id }}");

    @Schema(
        title = "Custom fields",
        description = "Additional key-value pairs merged into the template rendering context."
    )
    @PluginProperty(group = "destination")
    private Property<Map<String, Object>> customFields;

    @Schema(
        title = "Custom message",
        description = "Overrides the default templated message text when set."
    )
    @PluginProperty(group = "destination")
    private Property<String> customMessage;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        this.templateUri = Property.ofValue("matrix-template.peb");
        this.templateRenderMap = Property.ofValue(ExecutionService.executionMap(runContext, this));
        return super.run(runContext);
    }
}
