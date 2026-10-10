import { queryOptions, type QueryClient } from "@tanstack/react-query";
import type { Member } from "@/features/auth/types";
import { memberQueryKey } from "@/features/auth/session-cache";
import { getMembers, getRoster, getTeam, getTeams } from "./api";

// Partition team data by the current account and memberships so an old team roster
// cannot remain on screen after membership or leader authority changes.
export function directoryScope(member: Member) {
  return JSON.stringify([member.id, member.globalRole, member.teamMemberships.map(({ teamId, role }) => [teamId, role]).sort((a, b) => a[0].localeCompare(b[0]))]);
}
export const rosterQuery = (memberId: string) => queryOptions({ queryKey: ["admin", "roster", memberId], queryFn: ({ signal }) => getRoster(signal), staleTime: 0, retry: false });
export const membersQuery = (memberId: string) => queryOptions({ queryKey: ["admin", "members", memberId], queryFn: ({ signal }) => getMembers(signal), staleTime: 0, retry: false });
export const teamsQuery = (scope: string) => queryOptions({ queryKey: ["directory", scope, "teams"], queryFn: ({ signal }) => getTeams(signal), staleTime: 0, retry: false });
export const teamQuery = (scope: string, id: string) => queryOptions({ queryKey: ["directory", scope, "team", id], queryFn: ({ signal }) => getTeam(id, signal), staleTime: 0, retry: false });

export async function refreshDirectory(client: QueryClient) {
  await Promise.all([
    client.invalidateQueries({ queryKey: ["admin"] }),
    client.invalidateQueries({ queryKey: ["directory"] }),
    client.invalidateQueries({ queryKey: ["reservations"] }),
    client.invalidateQueries({ queryKey: memberQueryKey }),
  ]);
}

export async function pruneDirectoryCache(client: QueryClient, member: Member | null) {
  const scope = member ? directoryScope(member) : null;
  const predicate = (query: { queryKey: readonly unknown[] }) => ((query.queryKey[0] === "directory" || query.queryKey[0] === "reservations") && query.queryKey[1] !== scope)
    || (query.queryKey[0] === "admin" && member?.globalRole !== "GLOBAL_ADMIN");
  await client.cancelQueries({ predicate });
  client.removeQueries({ predicate });
}
