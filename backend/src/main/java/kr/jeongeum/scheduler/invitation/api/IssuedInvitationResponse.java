package kr.jeongeum.scheduler.invitation.api;

import java.time.Instant;
import kr.jeongeum.scheduler.invitation.domain.Invitation;

public record IssuedInvitationResponse(String id, String recipientName, String status,
        Instant expiresAt, Instant createdAt, String token, String rosterEntryId, String usedBy) {
    public static IssuedInvitationResponse from(Invitation invitation, String token) {
        return new IssuedInvitationResponse(Long.toString(invitation.id()), invitation.recipientName(),
                "AVAILABLE", invitation.expiresAt(), invitation.createdAt(), token,
                invitation.rosterEntryId() == null ? null : Long.toString(invitation.rosterEntryId()), null);
    }

    @Override
    public String toString() { return "IssuedInvitationResponse[id=" + id + ", token=redacted]"; }
}
