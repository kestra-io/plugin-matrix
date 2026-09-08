package io.kestra.plugin.matrix;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.HttpClientException;
import io.kestra.core.http.client.HttpClientResponseException;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;

public class MatrixApiService {
    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    public static void send(
        HttpClient client,
        String homeserverUrl,
        String accessToken,
        String roomId,
        String body,
        String msgtype,
        String transactionId,
        HttpRequest.HttpRequestBuilder requestBuilder
    ) throws ErrorSendingMessageException {
        MatrixMessage payload = new MatrixMessage(msgtype, body);

        String uri = normalizeHomeserverUrl(homeserverUrl)
            + "/_matrix/client/v3/rooms/"
            + encodePathSegment(roomId)
            + "/send/m.room.message/"
            + encodePathSegment(transactionId);

        requestBuilder
            .addHeader("Authorization", "Bearer " + accessToken)
            .addHeader("Content-Type", "application/json")
            .uri(URI.create(uri))
            .method("PUT")
            .body(
                HttpRequest.JsonRequestBody.builder()
                    .content(payload)
                    .build()
            );

        HttpRequest request = requestBuilder.build();

        try {
            HttpResponse<MatrixSendResponse> exchange = client.request(request, MatrixSendResponse.class);

            if (exchange.getStatus().getCode() != HttpResponse.Status.OK.getCode()
                || exchange.getBody() == null
                || exchange.getBody().eventId() == null) {
                throw new ErrorSendingMessageException(exchange.getStatus(), null, roomId);
            }
        } catch (HttpClientResponseException e) {
            HttpResponse<?> response = e.getResponse();
            MatrixError matrixError = response != null ? parseError(response.getBody()) : null;
            throw new ErrorSendingMessageException(response != null ? response.getStatus() : null, matrixError, roomId);
        } catch (IllegalVariableEvaluationException | HttpClientException e) {
            throw new ErrorSendingMessageException(
                "Unable to reach Matrix homeserver '" + homeserverUrl + "': " + e.getMessage()
                    + " — check that the homeserver URL is correct and reachable from the Kestra worker.",
                e
            );
        }
    }

    /**
     * Matrix uses the transaction ID to deduplicate retries of the same logical send, so it must stay
     * stable across attempts. The task run ID is constant for every attempt of a given task run, which
     * makes a Kestra {@code retry} of a send that actually reached the homeserver idempotent rather
     * than posting the message twice.
     */
    public static String transactionIdFor(RunContext runContext) {
        RunContext.TaskRunInfo taskRunInfo = runContext.taskRunInfo();

        return taskRunInfo != null && taskRunInfo.taskRunId() != null
            ? "kestra-" + taskRunInfo.taskRunId()
            : "kestra-" + UUID.randomUUID();
    }

    /**
     * A bearer token sent over plain {@code http} travels in cleartext. That is expected against a
     * local Synapse during development, so only a non-loopback host is worth warning about — a
     * misconfigured production homeserver would otherwise leak the token silently.
     */
    public static void warnIfTokenSentInCleartext(RunContext runContext, String homeserverUrl) {
        URI uri = URI.create(normalizeHomeserverUrl(homeserverUrl));

        if (!"http".equalsIgnoreCase(uri.getScheme()) || isLoopbackHost(uri.getHost())) {
            return;
        }

        runContext.logger().warn(
            "homeserverUrl '{}' uses plain http, so the Matrix access token is sent over the network in cleartext. Use https unless the homeserver is local.",
            homeserverUrl
        );
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return true;
        }

        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;

        return bare.equalsIgnoreCase("localhost")
            || bare.endsWith(".localhost")
            || bare.equals("::1")
            || bare.startsWith("127.");
    }

    /**
     * A homeserver or reverse proxy may return a non-JSON body (e.g. HTML on a 502/503),
     * so a parse failure must not itself throw — the caller falls back to the raw status.
     */
    private static MatrixError parseError(Object body) {
        if (body == null) {
            return null;
        }

        try {
            byte[] bytes = body instanceof byte[] rawBytes ? rawBytes : body.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length == 0) {
                return null;
            }
            return MAPPER.readValue(bytes, MatrixError.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeHomeserverUrl(String homeserverUrl) {
        if (!homeserverUrl.startsWith("http://") && !homeserverUrl.startsWith("https://")) {
            throw new IllegalArgumentException(
                "homeserverUrl '" + homeserverUrl + "' is missing a scheme — set it to a full URL, for example 'https://matrix.org'"
            );
        }

        return homeserverUrl.endsWith("/") ? homeserverUrl.substring(0, homeserverUrl.length() - 1) : homeserverUrl;
    }

    /**
     * '!' and ':' are legal in an RFC 3986 path segment, so encoding is not strictly required by
     * the grammar, but every mainstream Matrix client percent-encodes the room ID and it avoids
     * proxy/normalization surprises. URLEncoder is form (application/x-www-form-urlencoded)
     * encoding, not path encoding, so its '+' for space must be corrected to '%20'.
     */
    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatrixMessage(String msgtype, String body) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatrixSendResponse(@JsonProperty("event_id") String eventId) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatrixError(String errcode, String error, @JsonProperty("retry_after_ms") Integer retryAfterMs) {
    }

    public static class ErrorSendingMessageException extends Exception {
        public final HttpResponse.Status httpStatus;
        public final String errcode;
        public final String error;

        public ErrorSendingMessageException(String message, Throwable cause) {
            super(message, cause);
            this.httpStatus = null;
            this.errcode = null;
            this.error = null;
        }

        public ErrorSendingMessageException(HttpResponse.Status httpStatus, MatrixError matrixError, String roomId) {
            super(buildMessage(httpStatus, matrixError, roomId));
            this.httpStatus = httpStatus;
            this.errcode = matrixError != null ? matrixError.errcode() : null;
            this.error = matrixError != null ? matrixError.error() : null;
        }

        private static String buildMessage(HttpResponse.Status httpStatus, MatrixError matrixError, String roomId) {
            String errcode = matrixError != null ? matrixError.errcode() : null;
            String statusText = httpStatus != null ? String.valueOf(httpStatus.getCode()) : "unknown";
            String noDetail = matrixError != null && matrixError.error() != null ? matrixError.error() : "no further detail returned by the homeserver";

            String hint = switch (errcode == null ? "" : errcode) {
                case "M_FORBIDDEN" -> "the bot account must be invited to and have joined room '" + roomId + "'";
                case "M_UNKNOWN_TOKEN" -> "access token is invalid or was revoked (logging the bot account out invalidates its token)";
                case "M_LIMIT_EXCEEDED" -> "rate limited by the homeserver"
                    + (matrixError.retryAfterMs() != null ? "; retry after " + matrixError.retryAfterMs() + "ms" : "")
                    + " — consider adding a `retry` block to this task";
                case "M_TOO_LARGE" -> "the message is too large for the homeserver to accept";
                default -> noDetail;
            };

            return "Unable to send Matrix message (HTTP " + statusText + (errcode != null ? ", " + errcode : "") + "): " + hint;
        }
    }
}
