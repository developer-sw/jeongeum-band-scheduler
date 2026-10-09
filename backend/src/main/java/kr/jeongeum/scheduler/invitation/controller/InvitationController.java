package kr.jeongeum.scheduler.invitation.controller;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.MemberPrincipal;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.ApiResponse;
import kr.jeongeum.scheduler.invitation.api.InvitationResponse;
import kr.jeongeum.scheduler.invitation.api.IssuedInvitationResponse;
import kr.jeongeum.scheduler.invitation.service.InvitationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!bootstrap-admin")
public class InvitationController {
    private final InvitationService invitations;
    private final Clock clock;

    public InvitationController(InvitationService invitations, Clock clock) {
        this.invitations = invitations;
        this.clock = clock;
    }

    @PostMapping("/api/v1/invitations")
    public ResponseEntity<ApiResponse<IssuedInvitationResponse>> issue(
            @AuthenticationPrincipal MemberPrincipal principal, @RequestBody Issue input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(ApiResponse.of(
                invitations.issue(principal.memberId(), input.recipientName(), input.expiresAt(),
                        parseId(input.replacesInvitationId())), clock));
    }

    @GetMapping("/api/v1/invitations")
    public ResponseEntity<PageResponse> list(@AuthenticationPrincipal MemberPrincipal principal,
            @RequestParam(required = false) String cursor, @RequestParam(defaultValue = "50") int limit) {
        InvitationService.InvitationPage page = invitations.list(principal.memberId(), cursor, limit);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new PageResponse(
                new PageData(page.items()), new PageMeta(OffsetDateTime.now(clock.withZone(ZoneId.of("Asia/Seoul"))),
                        "Asia/Seoul", page.nextCursor())));
    }

    @DeleteMapping("/api/v1/invitations/{id}")
    public ResponseEntity<Void> revoke(@AuthenticationPrincipal MemberPrincipal principal, @PathVariable String id) {
        Long invitationId = parseId(id);
        if (invitationId == null) { throw ApiException.invalidInput(); }
        invitations.revoke(principal.memberId(), invitationId);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private static Long parseId(String value) {
        if (value == null) { return null; }
        try {
            long id = Long.parseLong(value);
            if (id < 1) { throw ApiException.invalidInput(); }
            return id;
        } catch (NumberFormatException exception) { throw ApiException.invalidInput(); }
    }

    public record Issue(String recipientName, Instant expiresAt, String replacesInvitationId) {}
    public record PageResponse(PageData data, PageMeta meta) {}
    public record PageData(List<InvitationResponse> items) {}
    public record PageMeta(OffsetDateTime serverTime, String timeZone, String nextCursor) {}
}
