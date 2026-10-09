package kr.jeongeum.scheduler.common.api;

import java.util.List;

public record ApiErrorResponse(Error error) {
    public static ApiErrorResponse of(String code, String message, String requestId) {
        return new ApiErrorResponse(new Error(code, message, requestId, List.of(), List.of()));
    }

    public record Error(String code, String message, String requestId,
            List<Object> fieldErrors, List<Object> conflicts) {}
}
