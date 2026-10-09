package kr.jeongeum.scheduler.common.security;

import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import org.springframework.stereotype.Service;

@Service
public class MemberAccess {
    private final MemberRepository members;
    public MemberAccess(MemberRepository members) { this.members = members; }

    public Member active(long id) { return requireActive(members.findById(id).orElseThrow(MemberAccess::missing)); }
    public Member activeForMutation(long id) {
        return requireActive(members.lockById(id).orElseThrow(MemberAccess::missing));
    }
    public Member administrator(long id) { return requireAdministrator(active(id)); }
    public Member administratorForMutation(long id) { return requireAdministrator(activeForMutation(id)); }

    private static Member requireActive(Member member) {
        if (!member.active()) { throw new ApiException(403, "INACTIVE_USER", "현재 이용할 수 없는 계정입니다."); }
        return member;
    }
    private static Member requireAdministrator(Member member) {
        if (!member.globalAdmin()) { throw forbidden(); }
        return member;
    }
    private static ApiException missing() {
        return new ApiException(401, "AUTHENTICATION_REQUIRED", "로그인이 필요합니다.");
    }
    public static ApiException forbidden() {
        return new ApiException(403, "FORBIDDEN", "요청을 수행할 권한이 없습니다.");
    }
}
