package kr.jeongeum.scheduler.exchange.api;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class ExchangeRequests {
    private ExchangeRequests() {}
    public record Item(String reservationId, Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
    public record Create(List<Item> offered, List<Item> requested) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
    public record Allocation(Long expectedVersion, String reservationId, List<String> instrumentCodesOrder) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
    public record Action(Long expectedVersion) {
        @JsonAnySetter public void rejectUnknown(String field,Object value) { throw ApiException.invalidInput(); }
    }
}
