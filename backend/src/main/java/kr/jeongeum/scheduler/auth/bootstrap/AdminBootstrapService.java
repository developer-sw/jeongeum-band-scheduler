package kr.jeongeum.scheduler.auth.bootstrap;

import java.time.Clock;
import kr.jeongeum.scheduler.auth.service.AuthenticationInputs;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("bootstrap-admin")
public class AdminBootstrapService {
    private final MemberRepository members;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public AdminBootstrapService(MemberRepository members, PasswordEncoder passwords, Clock clock) {
        this.members = members;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    public void initialize(String inputLoginId, String password, String inputDisplayName) {
        String loginId = AuthenticationInputs.loginId(inputLoginId);
        String displayName = AuthenticationInputs.name(inputDisplayName);
        AuthenticationInputs.password(password);
        members.lockBootstrapGuard();
        if (members.anyMemberExists()) {
            throw new IllegalStateException("Administrator initialization requires an empty member table");
        }
        members.insert(loginId, passwords.encode(password), displayName, "GLOBAL_ADMIN", clock.instant())
                .orElseThrow(() -> new IllegalStateException("Administrator initialization did not create an account"));
    }
}
