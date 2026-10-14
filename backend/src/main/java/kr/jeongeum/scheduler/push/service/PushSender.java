package kr.jeongeum.scheduler.push.service;

import java.time.Duration;
import kr.jeongeum.scheduler.push.domain.PushDelivery;

public interface PushSender {
    boolean enabled();
    Result send(PushDelivery delivery);
    record Result(int status,String errorCode,Duration retryAfter) {
        public static Result response(int status,Duration retryAfter) { return new Result(status,"HTTP_"+status,retryAfter); }
        public static Result transientFailure(String code) { return new Result(0,code,null); }
    }
}
