package kr.jeongeum.scheduler.exchange.controller;

import java.time.Clock;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.exchange.api.*;
import kr.jeongeum.scheduler.exchange.service.ExchangeService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
public class ExchangeController {
    private final ExchangeService exchanges;
    private final Clock clock;
    public ExchangeController(ExchangeService exchanges,Clock clock) { this.exchanges=exchanges;this.clock=clock; }
    @GetMapping("/api/v1/exchange-candidates")
    public ResponseEntity<ApiResponse<ExchangeCandidatesResponse>> candidates(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(required=false) String from,@RequestParam(required=false) String to) {
        return response(exchanges.candidates(actor.memberId(),from,to),200);
    }
    @GetMapping("/api/v1/exchange-requests")
    public ResponseEntity<ApiResponse<ExchangeListResponse>> list(@AuthenticationPrincipal MemberPrincipal actor) { return response(exchanges.list(actor.memberId()),200); }
    @GetMapping("/api/v1/exchange-requests/{id}")
    public ResponseEntity<ApiResponse<ExchangeResponse>> detail(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id) {
        return response(exchanges.detail(actor.memberId(),DirectoryInputs.id(id)),200);
    }
    @PostMapping("/api/v1/exchange-requests")
    public ResponseEntity<ApiResponse<ExchangeResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ExchangeRequests.Create input) {
        var result=exchanges.create(actor.memberId(),key,input);return response(result.response(),result.status());
    }
    @PatchMapping("/api/v1/exchange-requests/{id}/instrument-allocation")
    public ResponseEntity<ApiResponse<ExchangeResponse>> allocate(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ExchangeRequests.Allocation input) {
        return response(exchanges.allocate(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    @PostMapping("/api/v1/exchange-requests/{id}/approve")
    public ResponseEntity<ApiResponse<ExchangeResponse>> approve(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ExchangeRequests.Action input) {
        return response(exchanges.approve(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    @PostMapping("/api/v1/exchange-requests/{id}/reject")
    public ResponseEntity<ApiResponse<ExchangeResponse>> reject(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ExchangeRequests.Action input) {
        return response(exchanges.reject(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    @PostMapping("/api/v1/exchange-requests/{id}/cancel")
    public ResponseEntity<ApiResponse<ExchangeResponse>> cancel(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id,
            @RequestHeader(name="Idempotency-Key",required=false) String key,@RequestBody ExchangeRequests.Action input) {
        return response(exchanges.cancel(actor.memberId(),DirectoryInputs.id(id),key,input),200);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data,int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data,clock));
    }
}
