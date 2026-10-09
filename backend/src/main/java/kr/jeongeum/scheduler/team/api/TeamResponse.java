package kr.jeongeum.scheduler.team.api;

import kr.jeongeum.scheduler.team.domain.Team;

public record TeamResponse(String id, String name, String kind, String status, long version) {
    public static TeamResponse from(Team team) {
        return new TeamResponse(Long.toString(team.id()), team.name(), team.kind(), team.status(), team.version());
    }
}
