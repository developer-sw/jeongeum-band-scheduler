package kr.jeongeum.scheduler.member.api;

public record AdminMemberResponse(String id, String loginId, String displayName, String globalRole,
        String status, String rosterEntryId, long version) {}
