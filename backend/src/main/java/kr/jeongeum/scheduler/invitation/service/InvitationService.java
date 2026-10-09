package kr.jeongeum.scheduler.invitation.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import kr.jeongeum.scheduler.auth.security.AuthenticationAttemptLimiter;
import kr.jeongeum.scheduler.auth.service.AuthenticationInputs;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.invitation.api.InvitationResponse;
import kr.jeongeum.scheduler.invitation.api.IssuedInvitationResponse;
import kr.jeongeum.scheduler.invitation.domain.Invitation;
import kr.jeongeum.scheduler.invitation.repository.InvitationRepository;
import kr.jeongeum.scheduler.roster.domain.RosterEntry;
import kr.jeongeum.scheduler.roster.repository.RosterRepository;
import kr.jeongeum.scheduler.roster.service.RosterService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InvitationService {
    private final InvitationRepository invitations;
    private final AuthenticationAttemptLimiter limiter;
    private final Clock clock;
    private final Duration defaultExpiry;
    private final RosterRepository roster;
    private final DirectoryMutationGuard guard;
    private final MemberAccess access;

    public InvitationService(InvitationRepository invitations,
            AuthenticationAttemptLimiter limiter, Clock clock,
            @Value("${app.auth.invitation-validity:7d}") Duration defaultExpiry,
            RosterRepository roster, DirectoryMutationGuard guard, MemberAccess access) {
        this.invitations = invitations;
        this.limiter = limiter;
        this.clock = clock;
        this.defaultExpiry = defaultExpiry;
        this.roster = roster;
        this.guard = guard;
        this.access = access;
    }

    @Transactional
    public IssuedInvitationResponse issue(long actorId, String recipientName,
            Instant expiresAt, Long replacesInvitationId) {
        return issue(actorId, recipientName, null, expiresAt, replacesInvitationId);
    }

    @Transactional
    public IssuedInvitationResponse issue(long actorId, String recipientName, Long requestedRosterId,
            Instant expiresAt, Long replacesInvitationId) {
        guard.lock();
        access.administratorForMutation(actorId);
        Instant now = clock.instant();
        Instant expiry = expiresAt == null ? now.plus(defaultExpiry) : expiresAt;
        if (!expiry.isAfter(now)) { throw ApiException.invalidInput(); }
        Long rosterId = requestedRosterId;
        if (replacesInvitationId != null) {
            Invitation previous = invitations.lockById(replacesInvitationId).orElseThrow(() ->
                    new ApiException(404, "RESOURCE_NOT_FOUND", "초대를 찾을 수 없습니다."));
            if (previous.usedAt() != null) { throw alreadyUsed(); }
            if (previous.revokedAt() != null) { throw InvitationTokens.unavailable(); }
            if (previous.rosterEntryId() != null) {
                if (requestedRosterId != null && !requestedRosterId.equals(previous.rosterEntryId())) {
                    throw ApiException.invalidInput();
                }
                rosterId = previous.rosterEntryId();
            } else {
                // A legacy invite has no identity binding. Establish a new one, never match by name.
                if (requestedRosterId != null) { throw ApiException.invalidInput(); }
                rosterId = roster.insert(previous.recipientName(), now).id();
                invitations.bindRoster(previous.id(), rosterId);
            }
            invitations.revoke(previous.id(), now);
        }
        RosterEntry entry = rosterId == null
                ? roster.insert(AuthenticationInputs.name(recipientName), now)
                : roster.lockById(rosterId).orElseThrow(DirectoryInputs::notFound);
        if (entry.userId() != null) { throw RosterService.alreadyLinked(); }
        if (invitations.pendingForRoster(entry.id())) {
            throw new ApiException(409, "INVITATION_ALREADY_EXISTS", "이 명단에는 대기 중인 초대가 있습니다. 재발급을 사용해 주세요.");
        }
        String token = InvitationTokens.create();
        Invitation invitation = invitations.insert(InvitationTokens.digest(token), entry.name(), actorId, now, expiry, entry.id());
        return IssuedInvitationResponse.from(invitation, token);
    }

    @Transactional
    public InvitationPage list(long actorId, String cursor, int limit) {
        access.administrator(actorId);
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
        guard.lock();
        access.administratorForMutation(actorId);
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

    private static ApiException alreadyUsed() {
        return new ApiException(409, "INVITATION_ALREADY_USED", "이미 사용된 초대는 해지하거나 재발급할 수 없습니다.");
    }

    public record InvitationPage(List<InvitationResponse> items, String nextCursor) {}
}
