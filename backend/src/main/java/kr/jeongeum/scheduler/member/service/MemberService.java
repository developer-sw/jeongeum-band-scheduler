package kr.jeongeum.scheduler.member.service;

import java.time.Clock;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.member.api.AdminMemberResponse;
import kr.jeongeum.scheduler.member.api.MemberResponse;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemberService {
    private final MemberRepository members;
    private final MemberAccess access;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    private final kr.jeongeum.scheduler.exchange.service.ExchangeService exchanges;
    public MemberService(MemberRepository members, MemberAccess access, DirectoryMutationGuard guard, Clock clock,
            kr.jeongeum.scheduler.exchange.service.ExchangeService exchanges) {
        this.members = members; this.access = access; this.guard = guard; this.clock = clock; this.exchanges = exchanges;
    }
    @Transactional(readOnly = true)
    public MemberResponse current(long userId) {
        Member member = access.active(userId);
        return MemberResponse.from(member, members.memberships(userId));
    }
    @Transactional(readOnly = true)
    public List<AdminMemberResponse> list(long actorId) { access.administrator(actorId); return members.listAdminMembers(); }
    @Transactional(timeout = 10)
    public AdminMemberResponse changeRole(long actorId, long userId, String role, long version) {
        if (!"MEMBER".equals(role) && !"GLOBAL_ADMIN".equals(role)) { throw ApiException.invalidInput(); }
        guard.lock(); access.administratorForMutation(actorId);
        Member target = members.lockById(userId).orElseThrow(DirectoryInputs::notFound);
        DirectoryInputs.sameVersion(target.version(), version);
        if (!target.globalRole().equals(role)) {
            if (target.active() && target.globalAdmin() && "MEMBER".equals(role)
                    && members.activeAdministratorCount() <= 1) {
                throw new ApiException(409, "LAST_GLOBAL_ADMIN", "마지막 활성 전체 관리자의 권한은 해제할 수 없습니다.");
            }
            members.changeRole(userId, role);
            members.auditRole(userId, actorId, target.globalRole(), role, clock.instant());
            exchanges.refreshPendingUnderGuard();
        }
        return members.adminResponse(userId);
    }
}
