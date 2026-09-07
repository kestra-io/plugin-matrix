package io.kestra.plugin.matrix;

import java.util.Arrays;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;

import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.VoidOutput;
import io.kestra.core.runners.RunContext;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
@Schema(
    title = "Send a Matrix room message",
    description = """
        Posts a `m.room.message` event to a Matrix room using the Client-Server API `send` endpoint. `payload`
        is the plain-text message body; `msgtype` selects how clients render it. The bot account identified by
        `accessToken` must already be a member of `roomId` — it will not be invited automatically. Does not
        support end-to-end encrypted rooms."""
)
@Plugin(
    examples = {
        @Example(
            title = "Send a Matrix message on a failed flow execution.",
            full = true,
            code = """
                id: unreliable_flow
                namespace: company.team

                tasks:
                  - id: fail
                    type: io.kestra.plugin.scripts.shell.Commands
                    runner: PROCESS
                    commands:
                      - exit 1

                errors:
                  - id: alert_on_failure
                    type: io.kestra.plugin.matrix.Send
                    homeserverUrl: "https://matrix.org"
                    accessToken: "{{ secret('MATRIX_ACCESS_TOKEN') }}"
                    roomId: "!OGEhHVWSdvArJzumhm:matrix.org"
                    msgtype: NOTICE
                    payload: "Matrix Alert: flow {{ flow.id }} failed"
                """
        )
    }
)
public class Send extends AbstractConnection {
    @Schema(
        title = "Homeserver URL",
        description = """
            Base URL of the Matrix homeserver hosting the room, for example `https://matrix.org`. Matrix is
            federated so there is no default — use the homeserver the bot account is registered on.""",
        example = "https://matrix.org"
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> homeserverUrl;

    @Schema(
        title = "Bot access token",
        description = """
            Matrix access token for the bot account; store as a secret and avoid hardcoding. Obtain it via the login
            endpoint or Element under Settings > Help & About > Access Token."""
    )
    @NotNull
    @PluginProperty(secret = true, group = "connection")
    @ToString.Exclude
    protected Property<String> accessToken;

    @Schema(
        title = "Room ID",
        description = """
            Target Matrix room's internal ID, starting with `!` (for example `!OGEhHVWSdvArJzumhm:matrix.org`). Find
            it in Element under the room's Settings > Advanced. A room alias (starting with `#`) is not accepted
            here."""
    )
    @NotNull
    @PluginProperty(group = "main")
    protected Property<String> roomId;

    @Schema(
        title = "Message payload",
        description = """
            Plain-text message body sent as the event's `body` field. Do not wrap the value in a JSON object;
            combine with `msgtype` to control how Matrix clients render it."""
    )
    @PluginProperty(group = "main")
    protected Property<String> payload;

    @Schema(
        title = "Message type",
        description = """
            Matrix `msgtype` for the event: TEXT (`m.text`, a plain message), NOTICE (`m.notice`, intended for
            automated/bot senders — clients render it distinctly and must never auto-reply to it, which avoids
            bot-to-bot loops), or EMOTE (`m.emote`, an action-style message, e.g. "/me is done"). Defaults to TEXT;
            NOTICE is recommended for alerting flows.""",
        example = "NOTICE"
    )
    @PluginProperty(group = "main")
    protected Property<MsgType> msgtype;

    @Override
    public VoidOutput run(RunContext runContext) throws Exception {
        var rHomeserverUrl = runContext.render(this.homeserverUrl).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("homeserverUrl is required — set it to your Matrix homeserver base URL, for example 'https://matrix.org'"));
        var rAccessToken = runContext.render(this.accessToken).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("accessToken is required — set it to the bot account's Matrix access token"));
        var rRoomId = runContext.render(this.roomId).as(String.class)
            .orElseThrow(() -> new IllegalArgumentException("roomId is required — set it to the target room's internal ID, for example '!opaque:example.org'"));
        var rPayload = runContext.render(this.payload).as(String.class).orElse(null);
        var rMsgType = runContext.render(this.msgtype).as(MsgType.class).orElse(MsgType.TEXT);

        if (rPayload == null || rPayload.isEmpty()) {
            throw new IllegalArgumentException("payload must not be empty — Matrix requires a non-empty message body");
        }

        if (!rRoomId.startsWith("!")) {
            throw new IllegalArgumentException(
                "roomId '" + rRoomId + "' does not look like a Matrix room ID — it must start with '!' (e.g. '!opaque:example.org'). " +
                    "Find it in Element under the room's Settings > Advanced; a room alias (starting with '#') is not accepted here."
            );
        }

        HttpRequest.HttpRequestBuilder requestBuilder = createRequestBuilder(runContext);

        try (HttpClient httpClient = new HttpClient(runContext, super.httpClientConfigurationWithOptions())) {
            MatrixApiService.send(
                httpClient,
                rHomeserverUrl,
                rAccessToken,
                rRoomId,
                rPayload,
                rMsgType.getValue(),
                MatrixApiService.transactionIdFor(runContext),
                requestBuilder
            );
        }

        return null;
    }

    public enum MsgType {
        TEXT("m.text"),
        NOTICE("m.notice"),
        EMOTE("m.emote");

        private final String value;

        MsgType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        @JsonCreator
        public static MsgType fromString(String value) {
            if (value == null) {
                return null;
            }
            for (MsgType msgType : MsgType.values()) {
                if (msgType.name().equals(value)) {
                    return msgType;
                }
            }
            throw new IllegalArgumentException(
                "Invalid msgtype value '" + value + "'. Valid values (case-sensitive): " +
                    Arrays.stream(MsgType.values()).map(Enum::name).collect(Collectors.joining(", "))
            );
        }
    }
}
