package kr.jeongeum.scheduler.roster.api;

import java.util.List;

public record RosterEntryResponse(String id, String name, String userId, String loginId,
        String displayName, String accountStatus, long version, String signupGlobalRole, List<LeaderTeam> leaderTeams) {
    public RosterEntryResponse { leaderTeams = List.copyOf(leaderTeams); }

    public record LeaderTeam(String id, String name, String kind) {}
}
