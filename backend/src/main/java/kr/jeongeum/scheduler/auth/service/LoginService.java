package kr.jeongeum.scheduler.auth.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import kr.jeongeum.scheduler.auth.security.AuthenticationAttemptLimiter;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LoginService {
    private final MemberRepository members;
    private final PasswordEncoder passwords;
    private final AuthenticationAttemptLimiter limiter;
    private final String dummyHash;

    public LoginService(MemberRepository members, PasswordEncoder passwords, AuthenticationAttemptLimiter limiter) {
        this.members = members;
        this.passwords = passwords;
        this.limiter = limiter;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public Member login(String inputLoginId, String password) {
        String loginId;
        try { loginId = AuthenticationInputs.loginId(inputLoginId); }
        catch (ApiException exception) { throw invalidCredentials(); }
        limiter.beforeLogin(loginId);
        Member candidate = members.findByLoginId(loginId).orElse(null);
        boolean validPasswordInput = password != null && password.getBytes(StandardCharsets.UTF_8).length <= 72
                && password.indexOf('\0') < 0;
        String candidatePassword = validPasswordInput ? password : "invalid-password-input";
        String hash = candidate == null ? dummyHash : candidate.passwordHash();
        boolean matches = limiter.passwordWork(() -> passwords.matches(candidatePassword, hash));
        if (candidate == null || !validPasswordInput || !matches) {
            limiter.failedLogin(loginId);
            throw invalidCredentials();
        }
        Member current = members.lockById(candidate.id()).orElse(null);
        if (current == null || !current.active() || !current.passwordHash().equals(candidate.passwordHash())) {
            limiter.failedLogin(loginId);
            throw invalidCredentials();
        }
        limiter.successfulLogin(loginId);
        return current;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(401, "INVALID_CREDENTIALS", "로그인 ID 또는 비밀번호를 확인해 주세요.");
    }
}
