package kr.jeongeum.scheduler.member.domain;

import java.time.Instant;

public record Member(long id, String loginId, String passwordHash, String displayName,
        String globalRole, String status, Instant createdAt, long version) {
    public boolean active() { return "ACTIVE".equals(status); }
    public boolean globalAdmin() { return "GLOBAL_ADMIN".equals(globalRole); }

    @Override
    public String toString() { return "Member[id=" + id + "]"; }
}
