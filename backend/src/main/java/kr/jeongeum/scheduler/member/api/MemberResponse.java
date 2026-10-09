package kr.jeongeum.scheduler.member.api;

import java.util.List;
import kr.jeongeum.scheduler.member.domain.Member;

public record MemberResponse(String id, String loginId, String displayName, String globalRole,
        String status, List<TeamMembershipResponse> teamMemberships) {
    public static MemberResponse from(Member member) {
        return new MemberResponse(Long.toString(member.id()), member.loginId(), member.displayName(),
                member.globalRole(), member.status(), List.of());
    }

    public static MemberResponse from(Member member, List<TeamMembershipResponse> memberships) {
        return new MemberResponse(Long.toString(member.id()), member.loginId(), member.displayName(),
                member.globalRole(), member.status(), memberships);
    }
}
