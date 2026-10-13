package kr.jeongeum.scheduler.instrument.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class InstrumentFaultRequests {
    private InstrumentFaultRequests() {}
    public record Create(String instrumentCode, Long expectedAssetVersion, String description) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
    public record Resolve(Long expectedVersion, Long expectedAssetVersion, String resolutionNote) {
        @JsonAnySetter public void rejectUnknown(String field, Object value) { throw ApiException.invalidInput(); }
    }
}
