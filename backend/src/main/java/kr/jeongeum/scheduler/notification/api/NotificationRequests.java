package kr.jeongeum.scheduler.notification.api;

import java.time.Instant;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class NotificationRequests {
    private NotificationRequests() {}
    public record ReadAll(Instant through) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
}
