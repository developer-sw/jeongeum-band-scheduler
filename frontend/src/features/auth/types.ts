export interface Member {
  id: string;
  loginId: string;
  displayName: string;
  globalRole: "MEMBER" | "GLOBAL_ADMIN";
  status: "ACTIVE";
  teamMemberships: unknown[];
}

export interface LoginInput { loginId: string; password: string }
export interface RegisterInput extends LoginInput { invitationToken: string; displayName: string }

export interface Invitation {
  id: string;
  recipientName: string;
  status: "AVAILABLE" | "USED" | "EXPIRED" | "REVOKED";
  createdAt: string;
  expiresAt: string;
  usedAt: string | null;
  revokedAt: string | null;
}

export interface IssuedInvitation {
  id: string;
  recipientName: string;
  status: "AVAILABLE";
  expiresAt: string;
  createdAt: string;
  token: string;
}

export interface InvitationInput {
  recipientName: string;
  expiresAt?: string;
  replacesInvitationId?: string;
}
