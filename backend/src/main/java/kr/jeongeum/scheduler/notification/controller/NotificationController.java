package kr.jeongeum.scheduler.notification.controller;

import java.time.Clock;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.notification.api.*;
import kr.jeongeum.scheduler.notification.service.NotificationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
public class NotificationController {
    private final NotificationService notifications;
    private final Clock clock;
    public NotificationController(NotificationService notifications,Clock clock) { this.notifications=notifications; this.clock=clock; }
    @GetMapping("/api/v1/notifications")
    public ResponseEntity<ApiResponse<NotificationListResponse>> list(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestParam(required=false) String beforeId,@RequestParam(defaultValue="false") boolean unreadOnly,
            @RequestParam(required=false) Integer limit) {
        return response(notifications.list(actor.memberId(),beforeId,unreadOnly,limit));
    }
    @PatchMapping("/api/v1/notifications/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> read(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id) {
        return response(notifications.read(actor.memberId(),DirectoryInputs.id(id)));
    }
    @PostMapping("/api/v1/notifications/read-all")
    public ResponseEntity<ApiResponse<NotificationReadAllResponse>> readAll(@AuthenticationPrincipal MemberPrincipal actor,
            @RequestBody NotificationRequests.ReadAll input) { return response(notifications.readAll(actor.memberId(),input)); }
    private <T> ResponseEntity<ApiResponse<T>> response(T data) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(data,clock));
    }
}
