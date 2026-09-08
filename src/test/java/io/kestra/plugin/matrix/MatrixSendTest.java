package io.kestra.plugin.matrix;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.junit.annotations.KestraTest;
import io.kestra.core.models.property.Property;
import io.kestra.core.runners.RunContext;
import io.kestra.core.runners.RunContextFactory;
import io.kestra.core.serializers.JacksonMapper;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import jakarta.inject.Inject;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToObject;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

@KestraTest
class MatrixSendTest {

    @Inject
    private ApplicationContext applicationContext;

    @Inject
    private RunContextFactory runContextFactory;

    private String homeserverUrl;

    @BeforeEach
    void setUp() {
        EmbeddedServer embeddedServer = applicationContext.getBean(EmbeddedServer.class);
        embeddedServer.start();
        homeserverUrl = embeddedServer.getURL().toString();

        FakeMatrixController.message = null;
        FakeMatrixController.authorizationHeader = null;
        FakeMatrixController.roomId = null;
        FakeMatrixController.forcedErrorCode = null;
    }

    @AfterEach
    void tearDown() {
        FakeMatrixController.message = null;
        FakeMatrixController.authorizationHeader = null;
        FakeMatrixController.roomId = null;
        FakeMatrixController.forcedErrorCode = null;
    }

    private Send taskWith(String roomId, String payload, Send.MsgType msgtype) {
        return Send.builder()
            .homeserverUrl(Property.ofValue(homeserverUrl))
            .accessToken(Property.ofValue("token"))
            .roomId(Property.ofValue(roomId))
            .payload(Property.ofValue(payload))
            .msgtype(msgtype != null ? Property.ofValue(msgtype) : null)
            .build();
    }

    private Send defaultTask() {
        return taskWith("!room:example.org", "Hello", null);
    }

    @Test
    void run() throws Exception {
        RunContext runContext = runContextFactory.of();

        Send task = defaultTask();
        task.run(runContext);

        assertThat(FakeMatrixController.authorizationHeader, is("Bearer token"));
        assertThat(FakeMatrixController.roomId, is("!room:example.org"));
        assertThat(FakeMatrixController.message, equalToObject(new MatrixApiService.MatrixMessage("m.text", "Hello")));
    }

    @Test
    void run_withNoticeMsgType_shouldSendNotice() throws Exception {
        RunContext runContext = runContextFactory.of();

        Send task = taskWith("!room:example.org", "Hello", Send.MsgType.NOTICE);
        task.run(runContext);

        assertThat(FakeMatrixController.message, equalToObject(new MatrixApiService.MatrixMessage("m.notice", "Hello")));
    }

    @Test
    void run_withEmoteMsgType_fromYaml_shouldSendEmote() throws Exception {
        RunContext runContext = runContextFactory.of();

        String yaml = """
            homeserverUrl: "%s"
            accessToken: "token"
            roomId: "!room:example.org"
            payload: "is testing"
            msgtype: EMOTE
            """.formatted(homeserverUrl);

        Send task = JacksonMapper.ofYaml().readValue(yaml, Send.class);
        task.run(runContext);

        assertThat(FakeMatrixController.message, equalToObject(new MatrixApiService.MatrixMessage("m.emote", "is testing")));
    }

    @Test
    void run_withLowercaseMsgType_fromYaml_shouldSendNotice() throws Exception {
        RunContext runContext = runContextFactory.of();

        String yaml = """
            homeserverUrl: "%s"
            accessToken: "token"
            roomId: "!room:example.org"
            payload: "Hello"
            msgtype: notice
            """.formatted(homeserverUrl);

        Send task = JacksonMapper.ofYaml().readValue(yaml, Send.class);
        task.run(runContext);

        assertThat(FakeMatrixController.message, equalToObject(new MatrixApiService.MatrixMessage("m.notice", "Hello")));
    }

    @Test
    void run_withRoomIdContainingSpecialCharacters_shouldEncodeAndDeliver() throws Exception {
        RunContext runContext = runContextFactory.of();

        Send task = taskWith("!OGEhHVWSdvArJzumhm:matrix.org", "Hello", null);
        task.run(runContext);

        assertThat(FakeMatrixController.roomId, is("!OGEhHVWSdvArJzumhm:matrix.org"));
    }

    @Test
    void run_withExpressionRendering_shouldRenderAllProperties() throws Exception {
        RunContext runContext = runContextFactory.of(Map.of(
            "homeserverUrl", homeserverUrl,
            "token", "expr-token",
            "roomId", "!expr:example.org",
            "message", "expr message"
        ));

        String yaml = """
            homeserverUrl: "{{ homeserverUrl }}"
            accessToken: "{{ token }}"
            roomId: "{{ roomId }}"
            payload: "{{ message }}"
            msgtype: NOTICE
            """;

        Send task = JacksonMapper.ofYaml().readValue(yaml, Send.class);
        task.run(runContext);

        assertThat(FakeMatrixController.authorizationHeader, is("Bearer expr-token"));
        assertThat(FakeMatrixController.roomId, is("!expr:example.org"));
        assertThat(FakeMatrixController.message, equalToObject(new MatrixApiService.MatrixMessage("m.notice", "expr message")));
    }

    @Test
    void run_withInvalidMsgType_shouldFailRendering() throws Exception {
        RunContext runContext = runContextFactory.of();

        String yaml = """
            homeserverUrl: "%s"
            accessToken: "token"
            roomId: "!room:example.org"
            payload: "Hello"
            msgtype: LOUD
            """.formatted(homeserverUrl);

        Send task = JacksonMapper.ofYaml().readValue(yaml, Send.class);

        // Property falls back to parsing the rendered scalar as raw JSON once our MsgType.fromString
        // rejects it, so the failure surfaces as a rendering error rather than our own message —
        // the descriptive "Invalid msgtype value" wording is covered directly below instead.
        IllegalVariableEvaluationException exception = assertThrows(IllegalVariableEvaluationException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("LOUD"));
    }

    @Test
    void run_withEmptyPayload_shouldThrowDescriptiveException() {
        RunContext runContext = runContextFactory.of();

        Send task = taskWith("!room:example.org", "", null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("payload must not be empty"));
    }

    @Test
    void run_withRoomAliasInsteadOfId_shouldThrowDescriptiveException() {
        RunContext runContext = runContextFactory.of();

        Send task = taskWith("#room:example.org", "Hello", null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> task.run(runContext));
        assertThat(exception.getMessage(), containsString("does not look like a Matrix room ID"));
    }

    @Test
    void run_withUnknownToken_shouldThrowDescriptiveException() {
        RunContext runContext = runContextFactory.of();
        FakeMatrixController.forcedErrorCode = "M_UNKNOWN_TOKEN";

        Send task = defaultTask();

        MatrixApiService.ErrorSendingMessageException exception = assertThrows(
            MatrixApiService.ErrorSendingMessageException.class,
            () -> task.run(runContext)
        );
        assertThat(exception.getMessage(), containsString("access token is invalid or was revoked"));
    }

    @Test
    void run_withForbidden_shouldThrowDescriptiveException() {
        RunContext runContext = runContextFactory.of();
        FakeMatrixController.forcedErrorCode = "M_FORBIDDEN";

        Send task = defaultTask();

        MatrixApiService.ErrorSendingMessageException exception = assertThrows(
            MatrixApiService.ErrorSendingMessageException.class,
            () -> task.run(runContext)
        );
        assertThat(exception.getMessage(), containsString("must be invited to and have joined room"));
    }

    @Test
    void run_withRateLimit_shouldThrowDescriptiveExceptionWithRetryAfter() {
        RunContext runContext = runContextFactory.of();
        FakeMatrixController.forcedErrorCode = "M_LIMIT_EXCEEDED";

        Send task = defaultTask();

        MatrixApiService.ErrorSendingMessageException exception = assertThrows(
            MatrixApiService.ErrorSendingMessageException.class,
            () -> task.run(runContext)
        );
        assertThat(exception.getMessage(), containsString("retry after 2000ms"));
    }

    @Test
    void run_withErrcodeButNoErrorMessage_shouldNotEmitNullInMessage() {
        RunContext runContext = runContextFactory.of();
        FakeMatrixController.forcedErrorCode = "NO_ERROR_FIELD";

        Send task = defaultTask();

        MatrixApiService.ErrorSendingMessageException exception = assertThrows(
            MatrixApiService.ErrorSendingMessageException.class,
            () -> task.run(runContext)
        );
        assertThat(exception.getMessage(), containsString("M_UNKNOWN"));
        assertThat(exception.getMessage(), not(containsString("null")));
    }

    @Test
    void run_withNonJsonErrorBody_shouldFallBackToStatus() {
        RunContext runContext = runContextFactory.of();
        FakeMatrixController.forcedErrorCode = "NON_JSON";

        Send task = defaultTask();

        MatrixApiService.ErrorSendingMessageException exception = assertThrows(
            MatrixApiService.ErrorSendingMessageException.class,
            () -> task.run(runContext)
        );
        assertThat(exception.getMessage(), containsString("HTTP 500"));
    }

    @Test
    void msgtype_fromString_validValues() {
        assertThat(Send.MsgType.fromString("TEXT"), equalToObject(Send.MsgType.TEXT));
        assertThat(Send.MsgType.fromString("NOTICE"), equalToObject(Send.MsgType.NOTICE));
        assertThat(Send.MsgType.fromString("EMOTE"), equalToObject(Send.MsgType.EMOTE));
        assertThat(Send.MsgType.fromString(null), equalToObject(null));
        // casing is not significant, so `msgtype: notice` in a flow works too
        assertThat(Send.MsgType.fromString("notice"), equalToObject(Send.MsgType.NOTICE));
        assertThat(Send.MsgType.fromString("Emote"), equalToObject(Send.MsgType.EMOTE));
    }

    @Test
    void msgtype_fromString_invalidValue_shouldThrowDescriptiveException() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> Send.MsgType.fromString("LOUD")
        );

        assertThat(
            exception.getMessage(),
            equalToObject("Invalid msgtype value 'LOUD'. Valid values: TEXT, NOTICE, EMOTE")
        );
    }
}
