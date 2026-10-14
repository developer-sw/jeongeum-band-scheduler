package kr.jeongeum.scheduler.push.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.push.api.PushRequests;
import kr.jeongeum.scheduler.push.api.PushSubscriptionResponse;
import kr.jeongeum.scheduler.push.service.PushSettings;
import kr.jeongeum.scheduler.push.service.PushSubscriptionService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!bootstrap-admin")
public class PushController {
    private final PushSubscriptionService subscriptions;
    private final PushSettings settings;
    private final Clock clock;
    public PushController(PushSubscriptionService subscriptions,PushSettings settings,Clock clock) { this.subscriptions=subscriptions; this.settings=settings; this.clock=clock; }
    @GetMapping("/api/v1/push/public-key")
    public ResponseEntity<ApiResponse<PublicKey>> key(@AuthenticationPrincipal MemberPrincipal actor) {
        return response(new PublicKey(settings.enabled(),settings.publicKey()));
    }
    @GetMapping("/api/v1/push-subscriptions")
    public ResponseEntity<ApiResponse<SubscriptionList>> list(@AuthenticationPrincipal MemberPrincipal actor,HttpServletRequest request) {
        return response(new SubscriptionList(subscriptions.list(actor.memberId(),request.getSession().getId())));
    }
    @PostMapping("/api/v1/push-subscriptions")
    public ResponseEntity<ApiResponse<PushSubscriptionResponse>> register(@AuthenticationPrincipal MemberPrincipal actor,HttpServletRequest request,@RequestBody PushRequests.Register input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.of(subscriptions.register(actor.memberId(),request.getSession().getId(),input),clock));
    }
    @DeleteMapping("/api/v1/push-subscriptions/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal MemberPrincipal actor,@PathVariable String id) {
        subscriptions.delete(actor.memberId(),DirectoryInputs.id(id)); return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
    private <T> ResponseEntity<ApiResponse<T>> response(T item) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.of(item,clock)); }
    public record PublicKey(boolean enabled,String publicKey) {}
    public record SubscriptionList(List<PushSubscriptionResponse> subscriptions) {}
}
