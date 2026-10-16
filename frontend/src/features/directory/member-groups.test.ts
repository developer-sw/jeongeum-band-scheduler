import { describe, expect, it } from "vitest";
import { groupMembers } from "./member-groups";
import type { AdminMember, LeaderTeam, RosterEntry } from "./types";

const team: LeaderTeam = { id: "81", name: "합성 정규조", kind: "REGULAR" };
const extraTeam: LeaderTeam = { id: "82", name: "합성 추가조", kind: "ADDITIONAL" };
function entry(id: string, patch: Partial<RosterEntry> = {}): RosterEntry {
  return { id, name: `합성 명단 ${id}`, userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 0, ...patch };
}
function account(id: string, patch: Partial<AdminMember> = {}): AdminMember {
  return { id, loginId: `fixture.account.${id}`, displayName: `합성 회원 ${id}`, globalRole: "MEMBER", status: "ACTIVE", rosterEntryId: null, version: 0, ...patch };
}
function linked(id: string, userId: string, patch: Partial<RosterEntry> = {}): RosterEntry {
  return entry(id, { userId, loginId: `fixture.account.${userId}`, displayName: `합성 회원 ${userId}`, accountStatus: "ACTIVE", ...patch });
}
function allKeys(groups: ReturnType<typeof groupMembers>) {
  return [...groups.admins, ...groups.leaders, ...groups.members].map((person) => person.key);
}

describe("Member directory sections preserve explicit identity and current roles", () => {
  it("puts an administrator who also leads teams only in the administrator section", () => {
    const admin = account("21", { globalRole: "GLOBAL_ADMIN", rosterEntryId: "31" });
    const leader = account("22", { rosterEntryId: "32" }); const ordinary = account("23", { rosterEntryId: "33" });
    const groups = groupMembers([
      linked("33", "23"), linked("32", "22", { leaderTeams: [team] }), linked("31", "21", { leaderTeams: [team, extraTeam] }),
    ], [ordinary, leader, admin]);
    expect(groups.admins.map((person) => person.account?.id)).toEqual(["21"]);
    expect(groups.admins[0].leaderTeams).toEqual([team, extraTeam]);
    expect(groups.leaders.map((person) => person.account?.id)).toEqual(["22"]);
    expect(groups.members.map((person) => person.account?.id)).toEqual(["23"]);
    expect(new Set(allKeys(groups)).size).toBe(3);
  });

  it("keeps same-named roster entries and unlinked accounts as separate people", () => {
    const groups = groupMembers([entry("31", { name: "합성 동명이인" }), entry("32", { name: "합성 동명이인" })], [
      account("21", { displayName: "합성 동명이인" }), account("22", { displayName: "합성 동명이인" }),
    ]);
    expect(groups.members.map((person) => person.key)).toEqual(["roster:31", "roster:32", "user:21", "user:22"]);
    expect(groups.members.every((person) => person.entry === null || person.account === null)).toBe(true);
  });

  it("joins a linked account by its exact string ID despite different names", () => {
    const person = account("9007199254740993", { displayName: "합성 새 닉네임", rosterEntryId: "31" });
    const roster = linked("31", person.id, { name: "합성 가입 전 이름" });
    const groups = groupMembers([roster], [person, account("9007199254740994", { displayName: roster.name })]);
    expect(groups.members).toHaveLength(2);
    expect(groups.members[0]).toMatchObject({ key: "user:9007199254740993", entry: roster, account: person });
    expect(groups.members[1].account?.id).toBe("9007199254740994");
  });

  it("supports an unregistered leader without inventing an administrator account", () => {
    const roster = entry("31", { leaderTeams: [extraTeam, team] }); const groups = groupMembers([roster], []);
    expect(groups.leaders).toEqual([{ key: "roster:31", entry: roster, account: null, leaderTeams: [extraTeam, team] }]);
    expect(groups.admins).toEqual([]); expect(groups.members).toEqual([]);
  });

  it("places an unregistered future administrator only in the admin section even when also assigned as a leader", () => {
    const roster = entry("31", { signupGlobalRole: "GLOBAL_ADMIN", leaderTeams: [team] });
    const groups = groupMembers([roster], []);
    expect(groups.admins).toEqual([{ key: "roster:31", entry: roster, account: null, leaderTeams: [team] }]);
    expect(groups.leaders).toEqual([]); expect(groups.members).toEqual([]);
  });

  it("uses an already linked account's current role instead of its old signup role", () => {
    const groups = groupMembers([
      linked("31", "21", { signupGlobalRole: "GLOBAL_ADMIN", leaderTeams: [team] }),
      linked("32", "22", { signupGlobalRole: "GLOBAL_ADMIN" }),
    ], [account("21", { rosterEntryId: "31" }), account("22", { rosterEntryId: "32" })]);
    expect(groups.admins).toEqual([]);
    expect(groups.leaders[0].account?.id).toBe("21"); expect(groups.members[0].account?.id).toBe("22");
  });

  it("does not infer a linked account's actual administrator role from a signup setting when its account response is missing", () => {
    const groups = groupMembers([linked("31", "21", { signupGlobalRole: "GLOBAL_ADMIN" })], []);
    expect(groups.admins).toEqual([]); expect(groups.members[0].key).toBe("user:21");
  });

  it("joins a stale unlinked roster response through the account's explicit roster ID and uses its actual role", () => {
    const roster = entry("31", { signupGlobalRole: "GLOBAL_ADMIN", leaderTeams: [team] });
    const current = account("21", { rosterEntryId: "31" }); const groups = groupMembers([roster], [current]);
    expect(groups.admins).toEqual([]); expect(groups.members).toEqual([]);
    expect(groups.leaders).toEqual([{ key: "user:21", entry: roster, account: current, leaderTeams: [team] }]);
  });

  it("keeps an inactive account out of the leader section when joining through its roster reference", () => {
    const groups = groupMembers([entry("31", { leaderTeams: [team] })], [account("21", { rosterEntryId: "31", status: "INACTIVE" })]);
    expect(groups.leaders).toEqual([]); expect(groups.members[0]).toMatchObject({ key: "user:21", leaderTeams: [] });
  });

  it("does not replace a roster's explicit account ID with a conflicting account roster reference", () => {
    const roster = linked("31", "21"); const unrelated = account("22", { rosterEntryId: "31", globalRole: "GLOBAL_ADMIN" });
    const groups = groupMembers([roster], [unrelated]);
    expect(groups.members[0]).toMatchObject({ key: "user:21", entry: roster, account: null });
    expect(groups.admins[0]).toMatchObject({ key: "user:22", entry: null, account: unrelated });
  });

  it("does not guess between conflicting account IDs that claim the same unlinked roster entry", () => {
    const groups = groupMembers([entry("31")], [account("21", { rosterEntryId: "31" }), account("22", { rosterEntryId: "31" })]);
    expect(groups.members.map((person) => person.key)).toEqual(["roster:31", "user:21", "user:22"]);
    expect(groups.members[0].account).toBeNull();
  });

  it.each(["INACTIVE", "WITHDRAWN"])("keeps a %s linked leader in the ordinary section", (status) => {
    const groups = groupMembers([linked("31", "21", { accountStatus: status, leaderTeams: [team] })], [account("21", { status, rosterEntryId: "31" })]);
    expect(groups.leaders).toEqual([]);
    expect(groups.members[0]).toMatchObject({ key: "user:21", leaderTeams: [] });
    expect(groups.members[0].account?.status).toBe(status);
  });

  it("does not use stale active leader data when the account is withdrawn", () => {
    const groups = groupMembers([linked("31", "21", { leaderTeams: [team] })], [account("21", { status: "WITHDRAWN", rosterEntryId: "31", version: 1 })]);
    expect(groups.leaders).toEqual([]); expect(groups.members[0].leaderTeams).toEqual([]);
  });

  it("does not promote a stale inactive roster snapshot even if the account list says active", () => {
    const groups = groupMembers([linked("31", "21", { accountStatus: "INACTIVE", leaderTeams: [team] })], [account("21", { rosterEntryId: "31" })]);
    expect(groups.leaders).toEqual([]); expect(groups.members).toHaveLength(1);
  });

  it.each(["INACTIVE", "WITHDRAWN"])("groups %s administrators by their stored global role and preserves their status", (status) => {
    const groups = groupMembers([linked("31", "21", { accountStatus: status, leaderTeams: [team] })], [account("21", { globalRole: "GLOBAL_ADMIN", status, rosterEntryId: "31" })]);
    expect(groups.admins).toHaveLength(1); expect(groups.admins[0].account?.status).toBe(status);
    expect(groups.admins[0].leaderTeams).toEqual([]); expect(groups.leaders).toEqual([]);
  });

  it("includes accounts without roster rows once and cannot infer team leadership from the global member role", () => {
    const groups = groupMembers([], [account("21", { globalRole: "GLOBAL_ADMIN" }), account("22"), account("23", { status: "WITHDRAWN", rosterEntryId: "99" })]);
    expect(groups.admins[0]).toMatchObject({ key: "user:21", entry: null, leaderTeams: [] });
    expect(groups.members.map((person) => person.key)).toEqual(["user:22", "user:23"]); expect(groups.leaders).toEqual([]);
  });

  it("keeps a linked roster row when its account payload is missing without matching an unrelated name", () => {
    const roster = linked("31", "21", { name: "합성 동명이인", leaderTeams: [team] });
    const groups = groupMembers([roster], [account("22", { displayName: roster.name })]);
    expect(groups.leaders[0]).toMatchObject({ key: "user:21", entry: roster, account: null });
    expect(groups.members[0]).toMatchObject({ key: "user:22", entry: null });
  });

  it("treats a missing or empty leadership list as ordinary membership", () => {
    const legacy = entry("31"); delete (legacy as Partial<RosterEntry>).leaderTeams;
    const groups = groupMembers([legacy, entry("32")], []);
    expect(groups.leaders).toEqual([]); expect(groups.members.map((person) => person.key)).toEqual(["roster:31", "roster:32"]);
  });

  it("deduplicates repeated team IDs without collapsing two different same-named teams", () => {
    const sameName = { ...extraTeam, name: team.name };
    const groups = groupMembers([entry("31", { leaderTeams: [team, { ...team }, sameName] })], []);
    expect(groups.leaders[0].leaderTeams).toEqual([team, sameName]);
  });

  it("uses the newest duplicate account version without creating another card or changing its original position", () => {
    const original = account("21", { globalRole: "GLOBAL_ADMIN" });
    const changed = { ...original, globalRole: "MEMBER" as const, status: "WITHDRAWN", version: 2 };
    const groups = groupMembers([], [original, account("22"), changed, original]);
    expect(groups.admins).toEqual([]); expect(groups.members.map((person) => person.key)).toEqual(["user:21", "user:22"]);
    expect(groups.members[0].account).toBe(changed);
  });

  it("keeps the first snapshot for contradictory duplicate versions instead of combining roles", () => {
    const original = account("21");
    const groups = groupMembers([], [original, { ...original, globalRole: "GLOBAL_ADMIN", status: "INACTIVE" }]);
    expect(groups.admins).toEqual([]); expect(groups.members).toHaveLength(1); expect(groups.members[0].account).toBe(original);
  });

  it("deduplicates roster snapshots and uses the account's explicit roster reference among conflicting linked rows", () => {
    const old = linked("31", "21", { version: 0 });
    const current = { ...old, name: "합성 최신 명단", version: 1 };
    const preferred = linked("32", "21", { leaderTeams: [team] });
    const groups = groupMembers([old, linked("33", "22"), current, preferred], [account("21", { rosterEntryId: "32" }), account("22", { rosterEntryId: "33" })]);
    expect(allKeys(groups).sort()).toEqual(["user:21", "user:22"]);
    expect(groups.leaders[0].entry).toBe(preferred); expect(groups.members[0].entry?.id).toBe("33");
  });

  it("keeps the first person position even when a later duplicate contains the preferred linked roster row", () => {
    const preferred = linked("32", "21");
    const groups = groupMembers([linked("31", "21"), linked("33", "22"), preferred], [account("21", { rosterEntryId: "32" }), account("22", { rosterEntryId: "33" })]);
    expect(groups.members.map((person) => person.key)).toEqual(["user:21", "user:22"]);
    expect(groups.members[0].entry).toBe(preferred);
  });

  it("uses the newest version of a repeated roster ID without mutating the inputs", () => {
    const first = entry("31"); const latest = { ...first, name: "합성 최신 이름", version: 2 };
    const groups = groupMembers([first, entry("32"), latest], []);
    expect(groups.members.map((person) => person.key)).toEqual(["roster:31", "roster:32"]);
    expect(groups.members[0].entry).toBe(latest); expect(first.name).toBe("합성 명단 31");
  });

  it("preserves roster source order within sections and appends account-only rows in account source order without mutation", () => {
    const rows = [linked("33", "23"), linked("31", "21"), entry("34"), linked("32", "22")];
    const accounts = [account("25"), account("22"), account("24"), account("21"), account("23")];
    const before = JSON.stringify({ rows, accounts }); const groups = groupMembers(rows, accounts);
    expect(groups.members.map((person) => person.key)).toEqual(["user:23", "user:21", "roster:34", "user:22", "user:25", "user:24"]);
    expect(JSON.stringify({ rows, accounts })).toBe(before);
  });

  it("returns three empty sections for an empty directory", () => {
    expect(groupMembers([], [])).toEqual({ admins: [], leaders: [], members: [] });
  });
});
