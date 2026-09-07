package io.kestra.plugin.matrix;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;

@Controller("/_matrix/client/v3/rooms")
public class FakeMatrixController {

    public static String authorizationHeader;
    public static String roomId;
    public static MatrixApiService.MatrixMessage message;
    public static String forcedErrorCode;

    @Put("/{roomId}/send/m.room.message/{txnId}")
    public HttpResponse<?> post(
        @PathVariable String roomId,
        @PathVariable String txnId,
        @Header("Authorization") String authorization,
        @Body MatrixApiService.MatrixMessage message
    ) {
        FakeMatrixController.roomId = roomId;
        FakeMatrixController.authorizationHeader = authorization;
        FakeMatrixController.message = message;

        if (forcedErrorCode != null) {
            return switch (forcedErrorCode) {
                case "M_UNKNOWN_TOKEN" -> HttpResponse.status(HttpStatus.UNAUTHORIZED)
                    .body(new MatrixApiService.MatrixError("M_UNKNOWN_TOKEN", "Unrecognised access token", null));
                case "M_FORBIDDEN" -> HttpResponse.status(HttpStatus.FORBIDDEN)
                    .body(new MatrixApiService.MatrixError("M_FORBIDDEN", "You are not invited to this room", null));
                case "M_LIMIT_EXCEEDED" -> HttpResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(new MatrixApiService.MatrixError("M_LIMIT_EXCEEDED", "Too many requests", 2000));
                case "NO_ERROR_FIELD" -> HttpResponse.status(HttpStatus.BAD_REQUEST)
                    .body(new MatrixApiService.MatrixError("M_UNKNOWN", null, null));
                case "NON_JSON" -> HttpResponse.serverError("<html>bad gateway</html>");
                default -> HttpResponse.serverError("<html>bad gateway</html>");
            };
        }

        return HttpResponse.ok(new MatrixApiService.MatrixSendResponse("$fake:matrix.org"));
    }
}
