# How to use the Matrix plugin

Send messages and execution summaries to [Matrix](https://spec.matrix.org/latest/client-server-api/) rooms via a bot account, using the Client-Server API `send` endpoint.

## Authentication

Every task requires:

- `homeserverUrl` — the base URL of the Matrix homeserver the bot account is registered on, for example `https://matrix.org`. Matrix is federated, so there is no default.
- `accessToken` — the bot account's access token, obtained via the `/login` endpoint or, in Element, under Settings > Help & About > Access Token. Store it as a [secret](https://kestra.io/docs/concepts/secret); it is never accepted as a flow input in plain text and is masked in logs.
- `roomId` — the target room's internal ID, starting with `!` (for example `!OGEhHVWSdvArJzumhm:matrix.org`), found in Element under the room's Settings > Advanced. A room alias (starting with `#`) is not accepted.

The bot account must already be a **member** of the room — invite it and have it join before sending, otherwise the homeserver returns `M_FORBIDDEN`. The plugin does not support end-to-end encrypted rooms (no maintained Java Olm/Megolm implementation exists); posting to an E2EE room will fail.

Connection properties, `accessToken` and `roomId` included, must be set on **both** `Send` and `Execution` — they cannot share a common superclass with the polling trigger model, so keep the two declarations in sync if you fork this plugin.

## Tasks

`io.kestra.plugin.matrix.Send` posts a single `m.room.message` event as a step within a flow. Set `payload` to the plain-text message body (never a JSON object — the task builds the event itself) and `msgtype` to `TEXT` (default), `NOTICE`, or `EMOTE`. Prefer `NOTICE` for alerting flows: Matrix clients render it distinctly from human messages and it must never trigger an automated reply, which avoids bot-to-bot loops.

`io.kestra.plugin.matrix.Execution` sends a structured execution summary — namespace, flow and execution IDs, status, failing task, duration, and an execution link — and is designed for use with a [Flow trigger](https://kestra.io/docs/workflow-components/triggers) in a dedicated monitoring namespace that watches other namespaces for failures.

## Gotchas

- **Rate limiting**: a `429` response (`M_LIMIT_EXCEEDED`) includes `retry_after_ms`; the plugin surfaces it in the error message rather than retrying automatically — add a `retry` block to the task if you want Kestra to retry.
- **Revoked or mistyped token**: both surface as `401 M_UNKNOWN_TOKEN`, since logging the bot account out server-side invalidates its token the same way a typo would.
- **Large messages**: homeservers cap event size at roughly 64 KiB and reject larger payloads with `M_TOO_LARGE`; the plugin does not truncate.
