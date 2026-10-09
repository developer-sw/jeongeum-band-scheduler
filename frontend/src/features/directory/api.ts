import { ApiError, apiRequest } from "@/lib/api/client";
import type { AdminMember, RosterEntry, Song, SongInput, Team, TeamDetail, TeamKind } from "./types";

async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "응답을 확인하지 못했어요.");
  return response.data;
}

const teamPath = (id: string): `/api/v1/${string}` => `/api/v1/teams/${encodeURIComponent(id)}`;
export const getRoster = (signal?: AbortSignal) => data(apiRequest<RosterEntry[]>("/api/v1/roster", { signal }));
export const createRosterEntry = (name: string, signal?: AbortSignal) => data(apiRequest<RosterEntry>("/api/v1/roster", { method: "POST", body: { name }, signal }));
export const updateRosterEntry = (entry: RosterEntry, name: string, signal?: AbortSignal) => data(apiRequest<RosterEntry>(`/api/v1/roster/${encodeURIComponent(entry.id)}`, { method: "PATCH", body: { name, version: entry.version }, signal }));
export const linkAccount = (entry: RosterEntry, userId: string, signal?: AbortSignal) => data(apiRequest<RosterEntry>(`/api/v1/roster/${encodeURIComponent(entry.id)}/link-account`, { method: "POST", body: { userId, version: entry.version }, signal }));
export const getMembers = (signal?: AbortSignal) => data(apiRequest<AdminMember[]>("/api/v1/users", { signal }));
export const updateGlobalRole = (member: AdminMember, globalRole: AdminMember["globalRole"], signal?: AbortSignal) => data(apiRequest<AdminMember>(`/api/v1/users/${encodeURIComponent(member.id)}/role`, { method: "PATCH", body: { globalRole, version: member.version }, signal }));
export const getTeams = (signal?: AbortSignal) => data(apiRequest<Team[]>("/api/v1/teams", { signal }));
export const createTeam = (name: string, kind: TeamKind, signal?: AbortSignal) => data(apiRequest<Team>("/api/v1/teams", { method: "POST", body: { name, kind }, signal }));
export const getTeam = (id: string, signal?: AbortSignal) => data(apiRequest<TeamDetail>(teamPath(id), { signal }));
export const updateTeam = (team: Team, name: string, signal?: AbortSignal) => data(apiRequest<Team>(teamPath(team.id), { method: "PATCH", body: { name, version: team.version }, signal }));
export const addTeamMember = (id: string, rosterEntryId: string, signal?: AbortSignal) => data(apiRequest<TeamDetail>(`${teamPath(id)}/members`, { method: "POST", body: { rosterEntryId }, signal }));
export const removeTeamMember = (id: string, rosterEntryId: string, signal?: AbortSignal) => apiRequest(`${teamPath(id)}/members/${encodeURIComponent(rosterEntryId)}`, { method: "DELETE", signal });
export const setTeamLeader = (id: string, rosterEntryId: string | null, signal?: AbortSignal) => data(apiRequest<TeamDetail>(`${teamPath(id)}/leader`, { method: "PUT", body: { rosterEntryId }, signal }));
export const createSong = (teamId: string, input: SongInput, signal?: AbortSignal) => data(apiRequest<Song>(`${teamPath(teamId)}/songs`, { method: "POST", body: input, signal }));
export const updateSong = (song: Song, input: SongInput & { sortOrder: number }, signal?: AbortSignal) => data(apiRequest<Song>(`${teamPath(song.teamId)}/songs/${encodeURIComponent(song.id)}`, { method: "PATCH", body: { ...input, version: song.version }, signal }));
export const deleteSong = (song: Song, signal?: AbortSignal) => apiRequest(`${teamPath(song.teamId)}/songs/${encodeURIComponent(song.id)}?version=${song.version}`, { method: "DELETE", signal });
