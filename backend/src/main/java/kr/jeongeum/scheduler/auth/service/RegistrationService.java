package kr.jeongeum.scheduler.auth.service;

import java.time.Clock;
import java.time.Instant;
import kr.jeongeum.scheduler.auth.security.AuthenticationAttemptLimiter;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.invitation.domain.Invitation;
import kr.jeongeum.scheduler.invitation.repository.InvitationRepository;
import kr.jeongeum.scheduler.invitation.service.InvitationTokens;
import kr.jeongeum.scheduler.member.api.MemberResponse;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.roster.domain.RosterEntry;
import kr.jeongeum.scheduler.roster.repository.RosterRepository;
import kr.jeongeum.scheduler.roster.service.RosterService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistrationService {
    private final InvitationRepository invitations;
    private final MemberRepository members;
    private final PasswordEncoder passwords;
    private final AuthenticationAttemptLimiter limiter;
    private final Clock clock;
    private final RosterRepository roster;
    private final DirectoryMutationGuard guard;

    public RegistrationService(InvitationRepository invitations, MemberRepository members,
            PasswordEncoder passwords, AuthenticationAttemptLimiter limiter, Clock clock,
            RosterRepository roster, DirectoryMutationGuard guard) {
        this.invitations = invitations;
        this.members = members;
        this.passwords = passwords;
        this.limiter = limiter;
        this.clock = clock;
        this.roster = roster;
        this.guard = guard;
    }

    @Transactional
    public MemberResponse register(String invitationToken, String loginId, String password, String displayName) {
        String token = InvitationTokens.requireToken(invitationToken);
        String normalizedLoginId = AuthenticationInputs.loginId(loginId);
        String name = AuthenticationInputs.name(displayName);
        AuthenticationInputs.password(password);
        limiter.beforeInvitation(token);
        // Expensive hashing happens before the directory lock; validity is checked after waiting.
        String hash = limiter.passwordWork(() -> passwords.encode(password));
        guard.lock();
        Invitation invitation = invitations.lockByDigest(InvitationTokens.digest(token))
                .orElseThrow(InvitationTokens::unavailable);
        Instant now = clock.instant();
        if (!invitation.available(now)) { throw InvitationTokens.unavailable(); }
        RosterEntry entry;
        if (invitation.rosterEntryId() == null) {
            entry = roster.insert(invitation.recipientName(), now);
            invitations.bindRoster(invitation.id(), entry.id());
        } else {
            entry = roster.lockById(invitation.rosterEntryId()).orElseThrow(DirectoryInputs::notFound);
        }
        if (entry.userId() != null) { throw RosterService.alreadyLinked(); }
        Member member = members.insert(normalizedLoginId, hash, name, "MEMBER", now)
                .orElseThrow(() -> new ApiException(409, "LOGIN_ID_TAKEN", "이미 사용 중인 로그인 ID입니다."));
        invitations.use(invitation.id(), member.id(), now);
        roster.link(entry.id(), member.id(), now);
        return MemberResponse.from(member, members.memberships(member.id()));
    }
}
