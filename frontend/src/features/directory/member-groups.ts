import type { AdminMember, LeaderTeam, RosterEntry } from "./types";

export interface DirectoryPerson {
  key: string;
  entry: RosterEntry | null;
  account: AdminMember | null;
  leaderTeams: LeaderTeam[];
}

/** Retain the original position while selecting the newest duplicate snapshot. */
function latestById<T extends { id: string; version: number }>(items: T[]) {
  const unique = new Map<string, T>();
  for (const item of items) {
    const previous = unique.get(item.id);
    if (!previous || item.version > previous.version) unique.set(item.id, item);
  }
  return unique;
}

function currentLeaderTeams(entry: RosterEntry | null, account: AdminMember | null): LeaderTeam[] {
  if (!entry) return [];
  // Never promote a stale roster snapshot of an inactive or withdrawn account.
  if (account && account.status !== "ACTIVE") return [];
  if (entry.userId !== null) {
    if (entry.accountStatus !== "ACTIVE") return [];
  } else if (entry.accountStatus !== null) return [];
  const unique = new Map<string, LeaderTeam>();
  for (const team of entry.leaderTeams ?? []) if (!unique.has(team.id)) unique.set(team.id, team);
  return [...unique.values()];
}

/** One person belongs to one section; joins use explicit IDs and never names. */
export function groupMembers(roster: RosterEntry[], accounts: AdminMember[]) {
  const groups: { admins: DirectoryPerson[]; leaders: DirectoryPerson[]; members: DirectoryPerson[] } = {
    admins: [], leaders: [], members: [],
  };
  const accountById = latestById(accounts);
  const accountByRosterId = new Map<string, AdminMember | null>();
  for (const account of accountById.values()) {
    if (account.rosterEntryId === null) continue;
    // Conflicting account IDs cannot be resolved by guessing who owns a name.
    accountByRosterId.set(account.rosterEntryId, accountByRosterId.has(account.rosterEntryId) ? null : account);
  }
  const linkedId = (entry: RosterEntry) => entry.userId ?? accountByRosterId.get(entry.id)?.id ?? null;
  const entries = [...latestById(roster).values()];
  const preferredEntry = new Map<string, RosterEntry>();
  for (const entry of entries) {
    const userId = linkedId(entry);
    if (userId === null) continue;
    // A contradictory duplicate must not create a second card. Prefer the
    // account's explicit roster ID when both snapshots refer to that account.
    if (!preferredEntry.has(userId) || accountById.get(userId)?.rosterEntryId === entry.id) {
      preferredEntry.set(userId, entry);
    }
  }
  const seenAccounts = new Set<string>();
  function append(entry: RosterEntry | null, account: AdminMember | null, key: string) {
    const person: DirectoryPerson = { key, entry, account, leaderTeams: currentLeaderTeams(entry, account) };
    if (account?.globalRole === "GLOBAL_ADMIN" || !account && entry?.userId === null && entry.signupGlobalRole === "GLOBAL_ADMIN") groups.admins.push(person);
    else if (person.leaderTeams.length) groups.leaders.push(person);
    else groups.members.push(person);
  }
  for (const entry of entries) {
    const userId = linkedId(entry);
    if (userId === null) {
      append(entry, null, `roster:${entry.id}`);
      continue;
    }
    if (seenAccounts.has(userId)) continue;
    seenAccounts.add(userId);
    append(preferredEntry.get(userId) ?? entry, accountById.get(userId) ?? null, `user:${userId}`);
  }
  for (const account of accountById.values()) {
    if (!seenAccounts.has(account.id)) append(null, account, `user:${account.id}`);
  }
  return groups;
}
