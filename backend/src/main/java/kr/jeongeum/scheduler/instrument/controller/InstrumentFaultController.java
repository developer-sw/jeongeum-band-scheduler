package kr.jeongeum.scheduler.instrument.controller;

import java.time.Clock;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultListResponse;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultRequests;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultResponse;
import kr.jeongeum.scheduler.instrument.service.InstrumentFaultService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!bootstrap-admin")
public class InstrumentFaultController {
    private final InstrumentFaultService faults;
    private final Clock clock;
    public InstrumentFaultController(InstrumentFaultService faults, Clock clock) { this.faults=faults; this.clock=clock; }
    @GetMapping("/api/v1/instrument-faults")
    public ResponseEntity<ApiResponse<InstrumentFaultListResponse>> list(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(required=false) String instrumentCode, @RequestParam(required=false) String beforeId,
            @RequestParam(required=false) Integer limit) {
        return response(faults.list(actor.memberId(),instrumentCode,beforeId,limit),200);
    }
    @PostMapping("/api/v1/instrument-faults")
    public ResponseEntity<ApiResponse<InstrumentFaultResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestHeader(name="Idempotency-Key",required=false) String key, @RequestBody InstrumentFaultRequests.Create input) {
        var result=faults.create(actor.memberId(),key,input);
        return response(result.response(),result.status());
    }
    @PostMapping("/api/v1/instrument-faults/{id}/resolve")
    public ResponseEntity<ApiResponse<InstrumentFaultResponse>> resolve(@AuthenticationPrincipal MemberPrincipal actor,
            @PathVariable String id, @RequestHeader(name="Idempotency-Key",required=false) String key,
            @RequestBody InstrumentFaultRequests.Resolve input) {
        return response(faults.resolve(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data, int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data,clock));
    }
}
