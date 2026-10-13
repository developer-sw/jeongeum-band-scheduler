package kr.jeongeum.scheduler.notice.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class NoticeRequests {
    private NoticeRequests() {}
    public record Create(String title, String body, Boolean pinned) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
    public record Update(Long expectedVersion, String title, String body, Boolean pinned) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
    public record Delete(Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
}
