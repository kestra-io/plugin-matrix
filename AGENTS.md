# Kestra Matrix Plugin

## What

- Provides plugin components under `io.kestra.plugin.matrix`.
- Includes classes `AbstractConnection`, `Send`, `Template`, `Execution`, and `MatrixApiService`.

## Why

- What user problem does this solve? Teams running Kestra flows need to notify a Matrix room (e.g. Element) about flow execution results or send ad-hoc messages as part of a flow, the same way other Kestra notification plugins do for Slack or Telegram.
- Why would a team adopt this plugin in a workflow? It lets a flow post a message to a Matrix room directly, or wire a Flow trigger to alert a monitoring room whenever another flow fails, without building custom HTTP calls.
- What operational/business outcome does it enable? Faster incident awareness for teams already using Matrix/Element for chat-ops, without adding a new notification channel to maintain.

## How

### Architecture

Single-module plugin. Source packages under `io.kestra.plugin`:

- `matrix`

Infrastructure dependencies (Docker Compose services):

- `app`

### Key Plugin Classes

- `io.kestra.plugin.matrix.AbstractConnection` — abstract base task holding shared HTTP client options (`RunnableTask<VoidOutput>`).
- `io.kestra.plugin.matrix.Send` — posts a single `m.room.message` event (plain text, `TEXT`/`NOTICE`/`EMOTE`) to a room.
- `io.kestra.plugin.matrix.Template` — abstract; renders a classpath Pebble template into `Send`'s `payload`.
- `io.kestra.plugin.matrix.Execution` — implements `ExecutionInterface`; sends a templated execution-result notification, meant to pair with a core Flow trigger.
- `io.kestra.plugin.matrix.MatrixApiService` — static helper that builds the Matrix Client-Server API request and translates `M_*` errors into actionable messages; never a flow `type:`.

Note: `io.kestra.plugin.matrix.Execution` shadows `io.kestra.core.models.executions.Execution` within its own package — the core type is referenced fully qualified there, never imported.

### Project Structure

```
plugin-matrix/
├── src/main/java/io/kestra/plugin/matrix/
├── src/main/resources/doc/io.kestra.plugin.matrix.md
├── src/main/resources/matrix-template.peb
├── src/test/java/io/kestra/plugin/matrix/
├── src/test/resources/flows/
├── build.gradle
└── README.md
```

## Local rules

- Base the wording on the implemented packages and classes, not on template README text.
- Task class names must not repeat "Matrix" (the namespace already carries it); `MatrixApiService` is the sole deliberate exception since it is never a flow `type:`.

## References

- https://kestra.io/docs/plugin-developer-guide
- https://kestra.io/docs/plugin-developer-guide/contribution-guidelines
