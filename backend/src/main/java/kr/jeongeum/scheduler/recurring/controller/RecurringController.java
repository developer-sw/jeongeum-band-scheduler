package kr.jeongeum.scheduler.recurring.controller;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.recurring.api.RecurringRequests;
import kr.jeongeum.scheduler.recurring.api.RecurringResponse;
import kr.jeongeum.scheduler.recurring.service.RecurringService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!bootstrap-admin")
public class RecurringController {
    private final RecurringService recurring;
    private final Clock clock;
    public RecurringController(RecurringService recurring, Clock clock) { this.recurring = recurring; this.clock = clock; }
    @GetMapping("/api/v1/recurring-assignments")
    public ResponseEntity<ApiResponse<List<RecurringResponse>>> list(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return response(recurring.list(actor.memberId(), from, to), 200);
    }
    @PostMapping("/api/v1/recurring-assignments/preview")
    public ResponseEntity<ApiResponse<RecurringResponse.Preview>> preview(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestBody RecurringRequests.Preview input) {
        return response(recurring.preview(actor.memberId(), input), 200);
    }
    @PostMapping("/api/v1/recurring-assignments")
    public ResponseEntity<ApiResponse<RecurringResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody RecurringRequests.Create input) {
        var result = recurring.create(actor.memberId(), key, input);
        return response(result.assignment(), result.status());
    }
    @PatchMapping("/api/v1/recurring-assignments/{id}")
    public ResponseEntity<ApiResponse<RecurringResponse>> update(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody RecurringRequests.Update input) {
        var result = recurring.update(actor.memberId(), DirectoryInputs.id(id), key, input);
        return response(result.assignment(), result.status());
    }
    @PostMapping("/api/v1/recurring-assignments/{id}/end")
    public ResponseEntity<ApiResponse<RecurringResponse>> end(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody RecurringRequests.End input) {
        var result = recurring.end(actor.memberId(), DirectoryInputs.id(id), key, input);
        return response(result.assignment(), result.status());
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data, int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data, clock));
    }
}
