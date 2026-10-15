package kr.jeongeum.scheduler.auth.service;

import java.util.function.Supplier;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.member.api.MemberResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Password verification finishes first; final identity and session publication share the closure guard. */
@Service
public class LoginSessionService {
    private final DirectoryMutationGuard guard;
    private final MemberAccess access;
    public LoginSessionService(DirectoryMutationGuard guard,MemberAccess access) {
        this.guard=guard; this.access=access;
    }
    @Transactional(timeout=10)
    public MemberResponse complete(long memberId,Supplier<MemberResponse> persistSession) {
        guard.lock(); access.activeForMutation(memberId);
        // Spring Session uses IMMEDIATE flushing. Once this callback returns, revocation can delete
        // the authenticated parent row; a late login cannot publish a new session after withdrawal.
        return persistSession.get();
    }
}
