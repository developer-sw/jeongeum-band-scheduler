package kr.jeongeum.scheduler.recurring.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class RecurringRequests {
    private RecurringRequests() {}
    public record Create(String teamId, Integer weekday, String blockCode, String effectiveDate) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
    public record Update(Long expectedVersion, Integer weekday, String blockCode, String effectiveDate) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
    public record End(Long expectedVersion, String endDate) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
    public record Preview(String assignmentId, Long expectedVersion, String teamId, Integer weekday,
            String blockCode, String effectiveDate) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
}
