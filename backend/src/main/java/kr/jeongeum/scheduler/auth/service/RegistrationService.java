package kr.jeongeum.scheduler.auth.service;

import java.time.Clock;
import java.time.Instant;
import kr.jeongeum.scheduler.auth.security.AuthenticationAttemptLimiter;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.invitation.domain.Invitation;
import kr.jeongeum.scheduler.invitation.repository.InvitationRepository;
import kr.jeongeum.scheduler.invitation.service.InvitationTokens;
import kr.jeongeum.scheduler.member.api.MemberResponse;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
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

    public RegistrationService(InvitationRepository invitations, MemberRepository members,
            PasswordEncoder passwords, AuthenticationAttemptLimiter limiter, Clock clock) {
        this.invitations = invitations;
        this.members = members;
        this.passwords = passwords;
        this.limiter = limiter;
        this.clock = clock;
    }

    @Transactional
    public MemberResponse register(String invitationToken, String loginId, String password, String displayName) {
        String token = InvitationTokens.requireToken(invitationToken);
        String normalizedLoginId = AuthenticationInputs.loginId(loginId);
        String name = AuthenticationInputs.name(displayName);
        AuthenticationInputs.password(password);
        limiter.beforeInvitation(token);
        // Expensive hashing happens before the invitation lock; validity is checked after the lock.
        String hash = limiter.passwordWork(() -> passwords.encode(password));
        Invitation invitation = invitations.lockByDigest(InvitationTokens.digest(token))
                .orElseThrow(InvitationTokens::unavailable);
        Instant now = clock.instant();
        if (!invitation.available(now)) { throw InvitationTokens.unavailable(); }
        Member member = members.insert(normalizedLoginId, hash, name, "MEMBER", now)
                .orElseThrow(() -> new ApiException(409, "LOGIN_ID_TAKEN", "이미 사용 중인 로그인 ID입니다."));
        invitations.use(invitation.id(), member.id(), now);
        return MemberResponse.from(member);
    }
}
