package kr.jeongeum.scheduler.roster.api;

public record RosterEntryResponse(String id, String name, String userId, String loginId,
        String displayName, String accountStatus, long version) {}
