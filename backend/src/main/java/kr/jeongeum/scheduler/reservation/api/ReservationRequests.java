package kr.jeongeum.scheduler.reservation.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class ReservationRequests {
    private ReservationRequests() {}
    public record Create(String subjectType, String teamId, String date, String blockCode, List<String> instrumentCodes) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
    public record Cancel(Long expectedVersion, String reason) {
        @JsonAnySetter
        public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
}
