package kr.jeongeum.scheduler.invitation.api;

import java.time.Instant;
import kr.jeongeum.scheduler.invitation.domain.Invitation;

public record InvitationResponse(String id, String recipientName, String status, Instant createdAt,
        Instant expiresAt, Instant usedAt, Instant revokedAt) {
    public static InvitationResponse from(Invitation invitation, Instant now) {
        return new InvitationResponse(Long.toString(invitation.id()), invitation.recipientName(),
                invitation.status(now), invitation.createdAt(), invitation.expiresAt(),
                invitation.usedAt(), invitation.revokedAt());
    }
}
