package kr.jeongeum.scheduler.push.api;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import kr.jeongeum.scheduler.common.api.ApiException;

public final class PushRequests {
    private PushRequests() {}
    public record Keys(String p256dh,String auth) {
        @JsonAnySetter public void unknown(String field,Object value) { throw ApiException.invalidInput(); }
        @Override public String toString() { return "Push keys [redacted]"; }
    }
    public record Register(String endpoint,Keys keys,String deviceName) {
        @JsonAnySetter public void unknown(String field,Object value) { throw ApiException.invalidInput(); }
        @Override public String toString() { return "Push registration [redacted]"; }
    }
}
