package kr.jeongeum.scheduler.notice.controller;

import java.time.Clock;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.notice.api.NoticeListResponse;
import kr.jeongeum.scheduler.notice.api.NoticeRequests;
import kr.jeongeum.scheduler.notice.api.NoticeResponse;
import kr.jeongeum.scheduler.notice.service.NoticeService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
public class NoticeController {
    private final NoticeService notices;
    private final Clock clock;
    public NoticeController(NoticeService notices,Clock clock) { this.notices=notices; this.clock=clock; }
    @GetMapping("/api/v1/notices")
    public ResponseEntity<ApiResponse<NoticeListResponse>> list(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(required=false) String cursor,@RequestParam(required=false) Integer limit) {
        return response(notices.list(actor.memberId(),cursor,limit),200);
    }
    @GetMapping("/api/v1/notices/{id}")
    public ResponseEntity<ApiResponse<NoticeResponse>> get(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id) {
        return response(notices.get(actor.memberId(),DirectoryInputs.id(id)),200);
    }
    @PostMapping("/api/v1/notices")
    public ResponseEntity<ApiResponse<NoticeResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody NoticeRequests.Create input) {
        var result=notices.create(actor.memberId(),key,input); return response(result.response(),result.status());
    }
    @PatchMapping("/api/v1/notices/{id}")
    public ResponseEntity<ApiResponse<NoticeResponse>> update(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody NoticeRequests.Update input) {
        return response(notices.update(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    @DeleteMapping("/api/v1/notices/{id}")
    public ResponseEntity<ApiResponse<NoticeResponse>> delete(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody NoticeRequests.Delete input) {
        return response(notices.delete(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data,int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data,clock));
    }
}
