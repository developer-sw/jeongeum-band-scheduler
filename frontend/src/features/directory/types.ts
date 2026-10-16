export type TeamKind = "REGULAR" | "ADDITIONAL";
export type MembershipRole = "MEMBER" | "LEADER";
export interface LeaderTeam { id: string; name: string; kind: TeamKind }

export interface RosterEntry {
  id: string;
  name: string;
  userId: string | null;
  loginId: string | null;
  displayName: string | null;
  accountStatus: string | null;
  signupGlobalRole: "MEMBER" | "GLOBAL_ADMIN";
  leaderTeams: LeaderTeam[];
  version: number;
}

export interface AdminMember {
  id: string;
  loginId: string;
  displayName: string;
  globalRole: "MEMBER" | "GLOBAL_ADMIN";
  status: string;
  rosterEntryId: string | null;
  version: number;
}

export interface Team { id: string; name: string; kind: TeamKind; status: "ACTIVE" | "DISBANDED"; version: number }
export interface TeamMember { rosterEntryId: string; name: string; userId: string | null; role: MembershipRole }
export interface Song { id: string; teamId: string; title: string; artist: string | null; memo: string | null; sortOrder: number; version: number }
export interface SongInput { title: string; artist?: string; memo?: string; sortOrder?: number }
export interface TeamDetail { team: Team; members: TeamMember[]; songs: Song[]; canViewMembers: boolean; canManageTeam: boolean; canManageSongs: boolean }
