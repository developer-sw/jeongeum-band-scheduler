package kr.jeongeum.scheduler.team.domain;

public record Team(long id, String name, String kind, String status, long version) {
    public boolean active() { return "ACTIVE".equals(status); }
}
