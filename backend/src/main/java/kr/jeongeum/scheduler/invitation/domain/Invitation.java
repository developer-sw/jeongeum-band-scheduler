package kr.jeongeum.scheduler.invitation.domain;

import java.time.Instant;

public record Invitation(long id, String digest, String recipientName, long issuedBy,
        Instant createdAt, Instant expiresAt, Instant usedAt, Long usedBy, Instant revokedAt, long version) {
    public boolean available(Instant now) {
        return usedAt == null && revokedAt == null && now.isBefore(expiresAt);
    }

    public String status(Instant now) {
        if (usedAt != null) { return "USED"; }
        if (revokedAt != null) { return "REVOKED"; }
        return now.isBefore(expiresAt) ? "AVAILABLE" : "EXPIRED";
    }

    @Override
    public String toString() { return "Invitation[id=" + id + "]"; }
}
