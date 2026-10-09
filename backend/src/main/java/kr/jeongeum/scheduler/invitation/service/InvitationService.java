package kr.jeongeum.scheduler.invitation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.AuthenticationAttemptLimiter;
import kr.jeongeum.scheduler.auth.service.AuthenticationInputs;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.invitation.api.InvitationResponse;
import kr.jeongeum.scheduler.invitation.api.IssuedInvitationResponse;
import kr.jeongeum.scheduler.invitation.domain.Invitation;
import kr.jeongeum.scheduler.invitation.repository.InvitationRepository;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
    private final InvitationRepository invitations;
    private final MemberRepository members;
    private final AuthenticationAttemptLimiter limiter;
    private final Clock clock;
    private final Duration defaultExpiry;

    public InvitationService(InvitationRepository invitations, MemberRepository members,
            AuthenticationAttemptLimiter limiter, Clock clock,
            @Value("${app.auth.invitation-validity:7d}") Duration defaultExpiry) {
        this.invitations = invitations;
        this.members = members;
        this.limiter = limiter;
        this.clock = clock;
        this.defaultExpiry = defaultExpiry;
    }

    @Transactional
    public IssuedInvitationResponse issue(long actorId, String recipientName,
            Instant expiresAt, Long replacesInvitationId) {
        requireAdministrator(actorId);
        String name = AuthenticationInputs.name(recipientName);
        if (replacesInvitationId != null) {
            Invitation previous = invitations.lockById(replacesInvitationId).orElseThrow(() ->
                    new ApiException(404, "RESOURCE_NOT_FOUND", "초대를 찾을 수 없습니다."));
            if (previous.usedAt() != null) { throw alreadyUsed(); }
            if (previous.revokedAt() != null) { throw InvitationTokens.unavailable(); }
            invitations.revoke(previous.id(), clock.instant());
        }
        Instant now = clock.instant();
        Instant expiry = expiresAt == null ? now.plus(defaultExpiry) : expiresAt;
        if (!expiry.isAfter(now)) { throw ApiException.invalidInput(); }
        String token = InvitationTokens.create();
        Invitation invitation = invitations.insert(InvitationTokens.digest(token), name, actorId, now, expiry);
        return IssuedInvitationResponse.from(invitation, token);
    }

    @Transactional
    public InvitationPage list(long actorId, String cursor, int limit) {
        requireAdministrator(actorId);
        if (limit < 1 || limit > 100) { throw ApiException.invalidInput(); }
        Long beforeId = null;
        if (cursor != null) {
            try {
                beforeId = Long.parseLong(cursor);
                if (beforeId < 1) { throw ApiException.invalidInput(); }
            } catch (NumberFormatException exception) { throw ApiException.invalidInput(); }
        }
        List<Invitation> rows = invitations.page(beforeId, limit + 1);
        boolean hasNext = rows.size() > limit;
        List<Invitation> page = rows.subList(0, Math.min(rows.size(), limit));
        Instant now = clock.instant();
        return new InvitationPage(page.stream().map(row -> InvitationResponse.from(row, now)).toList(),
                hasNext ? Long.toString(page.getLast().id()) : null);
    }

    @Transactional
    public void revoke(long actorId, long invitationId) {
        requireAdministrator(actorId);
        Invitation invitation = invitations.lockById(invitationId).orElseThrow(() ->
                new ApiException(404, "RESOURCE_NOT_FOUND", "초대를 찾을 수 없습니다."));
        if (invitation.usedAt() != null) { throw alreadyUsed(); }
        if (invitation.revokedAt() == null) { invitations.revoke(invitation.id(), clock.instant()); }
    }

    @Transactional(readOnly = true)
    public Instant inspect(String input) {
        String token = InvitationTokens.requireToken(input);
        limiter.beforeInvitation(token);
        Invitation invitation = invitations.findByDigest(InvitationTokens.digest(token))
                .orElseThrow(InvitationTokens::unavailable);
        if (!invitation.available(clock.instant())) { throw InvitationTokens.unavailable(); }
        return invitation.expiresAt();
    }

    private void requireAdministrator(long actorId) {
        Member actor = members.lockById(actorId).orElseThrow(() ->
                new ApiException(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다."));
        if (!actor.active()) { throw new ApiException(403, "INACTIVE_USER", "현재 이용할 수 없는 계정입니다."); }
        if (!actor.globalAdmin()) { throw new ApiException(403, "FORBIDDEN", "요청을 수행할 권한이 없습니다."); }
    }

    private static ApiException alreadyUsed() {
        return new ApiException(409, "INVITATION_ALREADY_USED", "이미 사용된 초대는 해지하거나 재발급할 수 없습니다.");
    }

    public record InvitationPage(List<InvitationResponse> items, String nextCursor) {}
}
