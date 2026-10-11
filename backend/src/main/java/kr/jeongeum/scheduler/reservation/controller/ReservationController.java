package kr.jeongeum.scheduler.reservation.controller;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.reservation.api.InstrumentResponse;
import kr.jeongeum.scheduler.reservation.api.ReservationChangeResponse;
import kr.jeongeum.scheduler.reservation.api.ReservationRequests;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;
import kr.jeongeum.scheduler.reservation.api.ScheduleResponse;
import kr.jeongeum.scheduler.reservation.service.ReservationService;
import kr.jeongeum.scheduler.reservation.service.ScheduleService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!bootstrap-admin")
public class ReservationController {
    private final ReservationService reservations;
    private final ScheduleService schedules;
    private final Clock clock;
    public ReservationController(ReservationService reservations, ScheduleService schedules, Clock clock) {
        this.reservations = reservations; this.schedules = schedules; this.clock = clock;
    }
    @GetMapping("/api/v1/schedule")
    public ResponseEntity<ApiResponse<ScheduleResponse>> schedule(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        return response(schedules.schedule(actor.memberId(), from, to), 200);
    }
    @GetMapping("/api/v1/instruments")
    public ResponseEntity<ApiResponse<List<InstrumentResponse>>> instruments(@AuthenticationPrincipal MemberPrincipal actor) {
        return response(schedules.instruments(actor.memberId()), 200);
    }
    @PostMapping("/api/v1/reservations")
    public ResponseEntity<ApiResponse<ReservationResponse>> create(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReservationRequests.Create input) {
        ReservationService.Result result = reservations.create(actor.memberId(), key, input);
        return response(result.reservation(), result.status());
    }
    @GetMapping("/api/v1/reservations")
    public ResponseEntity<ApiResponse<List<ReservationResponse>>> mine(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(defaultValue = "mine") String scope, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to) {
        return response(reservations.mine(actor.memberId(), scope, from, to), 200);
    }
    @GetMapping("/api/v1/reservations/{id}")
    public ResponseEntity<ApiResponse<ReservationResponse>> detail(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable String id) {
        return response(reservations.detail(actor.memberId(), DirectoryInputs.id(id)), 200);
    }
    @PostMapping("/api/v1/reservations/{id}/cancel")
    public ResponseEntity<ApiResponse<ReservationResponse>> cancel(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReservationRequests.Cancel input) {
        ReservationService.Result result = reservations.cancel(actor.memberId(), DirectoryInputs.id(id), key, input);
        return response(result.reservation(), result.status());
    }
    @PatchMapping("/api/v1/reservations/{id}")
    public ResponseEntity<ApiResponse<ReservationChangeResponse>> change(@AuthenticationPrincipal MemberPrincipal actor, @PathVariable String id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key, @RequestBody ReservationRequests.Change input) {
        return response(reservations.change(actor.memberId(), DirectoryInputs.id(id), key, input), 200);
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T data, int status) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(ApiResponse.of(data, clock));
    }
}
