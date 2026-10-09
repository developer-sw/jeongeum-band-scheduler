import type { Invitation, InvitationInput } from "./types";

export function invitationInput(selected: { id: string; name: string } | undefined, replacement?: Invitation, expiresAt?: string): InvitationInput {
  if (replacement) return {
    ...(replacement.rosterEntryId ? { rosterEntryId: replacement.rosterEntryId } : { recipientName: replacement.recipientName }),
    replacesInvitationId: replacement.id,
  };
  if (!selected) throw new TypeError("Select a roster entry before issuing an invitation.");
  return { rosterEntryId: selected.id, expiresAt };
}
