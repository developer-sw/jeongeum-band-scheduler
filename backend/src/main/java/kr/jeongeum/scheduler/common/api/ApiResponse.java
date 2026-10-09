package kr.jeongeum.scheduler.common.api;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;

public record ApiResponse<T>(T data, Meta meta) {
    public static <T> ApiResponse<T> of(T data, Clock clock) {
        return new ApiResponse<>(data,
                new Meta(OffsetDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))), "Asia/Seoul"));
    }

    public record Meta(OffsetDateTime serverTime, String timeZone) {}
}
