import { QueryClient } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { authErrorMessage } from "@/features/auth/errors";
import { invitationInput } from "@/features/auth/invitation-input";
import type { Invitation, Member } from "@/features/auth/types";
import { getRoster, linkAccount, updateGlobalRole, updateSignupRole, updateSong } from "./api";
import type { RosterEntry } from "./types";
import { directoryScope, pruneDirectoryCache } from "./queries";
import { nameError, songError } from "./validation";

const member: Member = { id: "9007199254740993", loginId: "fixture.member", displayName: "가상 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [{ teamId: "10", teamName: "가상 A조", kind: "REGULAR", role: "LEADER" }, { teamId: "11", teamName: "가상 B조", kind: "REGULAR", role: "MEMBER" }] };
const meta = { serverTime: "2026-10-09T00:00:00+09:00", timeZone: "Asia/Seoul" };
afterEach(() => vi.unstubAllGlobals());

describe("directory authority and current-state cache", () => {
  it("changes scope on membership, leader, account and global authority changes while ignoring order", () => {
    const original = directoryScope(member);
    expect(directoryScope({ ...member, teamMemberships: [...member.teamMemberships].reverse() })).toBe(original);
    expect(directoryScope({ ...member, teamMemberships: [member.teamMemberships[0]] })).not.toBe(original);
    expect(directoryScope({ ...member, teamMemberships: member.teamMemberships.map((item) => ({ ...item, role: "MEMBER" })) })).not.toBe(original);
    expect(directoryScope({ ...member, globalRole: "GLOBAL_ADMIN" })).not.toBe(original);
    expect(directoryScope({ ...member, id: "9007199254740994" })).not.toBe(original);
  });

  it("cancels old team reads and removes former private/admin data without removing the new scope", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    const previous = directoryScope({ ...member, globalRole: "GLOBAL_ADMIN" });
    let resolve!: (value: string) => void; let signal: AbortSignal | undefined;
    const pending = client.fetchQuery({ queryKey: ["directory", previous, "team", "10"], queryFn: (context) => { signal = context.signal; return new Promise<string>((done) => { resolve = done; }); } }).catch(() => undefined);
    client.setQueryData(["directory", directoryScope(member), "team", "11"], "current scoped data");
    client.setQueryData(["admin", "roster", member.id], "old roster names");
    await pruneDirectoryCache(client, member);
    resolve("late admin team names"); await pending;
    expect(signal?.aborted).toBe(true);
    expect(client.getQueryData(["directory", previous, "team", "10"])).toBeUndefined();
    expect(client.getQueryData(["admin", "roster", member.id])).toBeUndefined();
    expect(client.getQueryData(["directory", directoryScope(member), "team", "11"])).toBe("current scoped data");
    client.clear();
  });

  it("uses explicit string IDs and current versions for manual account linkage and authority changes", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta }) : Response.json({ data: {}, meta }));
    vi.stubGlobal("fetch", fetchMock);
    await linkAccount({ id: "9007199254740995", name: "동명이인", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 7 }, member.id);
    await updateGlobalRole({ ...member, rosterEntryId: null, version: 3 }, "GLOBAL_ADMIN");
    expect(fetchMock.mock.calls[1][0]).toBe("/api/v1/roster/9007199254740995/link-account");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ userId: "9007199254740993", version: 7 });
    expect(JSON.parse(fetchMock.mock.calls[3][1].body)).toEqual({ globalRole: "GLOBAL_ADMIN", version: 3 });
  });

  it("preserves signup authority and all leader team IDs from the administrator roster response", async () => {
    const rows: RosterEntry[] = [{ id: "31", name: "합성 예정 관리자", userId: null, loginId: null, displayName: null, accountStatus: null,
      signupGlobalRole: "GLOBAL_ADMIN", leaderTeams: [{ id: "10", name: "합성 정규조", kind: "REGULAR" }, { id: "11", name: "합성 추가조", kind: "ADDITIONAL" }], version: 3 }];
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ data: rows, meta })); vi.stubGlobal("fetch", fetchMock);
    const controller = new AbortController(); expect(await getRoster(controller.signal)).toEqual(rows);
    expect(fetchMock).toHaveBeenCalledOnce(); expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/roster");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "GET", cache: "no-store", credentials: "same-origin", signal: controller.signal });
  });

  it("changes only the selected roster signup role through the versioned CSRF-protected API", async () => {
    const roster: RosterEntry = { id: "9007199254740995", name: "합성 미가입 명단", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "MEMBER", leaderTeams: [], version: 7 };
    const updated = { ...roster, signupGlobalRole: "GLOBAL_ADMIN", version: 8 };
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta }) : Response.json({ data: updated, meta }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await updateSignupRole(roster, "GLOBAL_ADMIN")).toEqual(updated);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const request = fetchMock.mock.calls[1];
    expect(request[0]).toBe("/api/v1/roster/9007199254740995/signup-role");
    expect(request[1].method).toBe("PATCH");
    expect(JSON.parse(request[1].body)).toEqual({ globalRole: "GLOBAL_ADMIN", version: 7 });
    expect(new Headers(request[1].headers).get("X-CSRF-TOKEN")).toBe("fixture-csrf");
    expect(JSON.parse(request[1].body)).not.toHaveProperty("userId");
  });

  it("does not replay a signup-role version conflict or send an offline role change", async () => {
    const roster: RosterEntry = { id: "31", name: "합성 미가입 명단", userId: null, loginId: null, displayName: null, accountStatus: null, signupGlobalRole: "GLOBAL_ADMIN", leaderTeams: [], version: 3 };
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta }))
      .mockResolvedValueOnce(Response.json({ error: { code: "VERSION_CONFLICT", message: "synthetic private diagnostic" } }, { status: 409 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(updateSignupRole(roster, "MEMBER")).rejects.toMatchObject({ status: 409, code: "VERSION_CONFLICT" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    vi.stubGlobal("navigator", { onLine: false });
    await expect(updateSignupRole(roster, "MEMBER")).rejects.toMatchObject({ status: 0, code: "OFFLINE" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("preserves a song's own team path and version, and does not replay a conflicting edit", async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta })).mockResolvedValueOnce(Response.json({ error: { code: "VERSION_CONFLICT", message: "internal echo must not be rendered" } }, { status: 409 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(updateSong({ id: "12", teamId: "11", title: "가상 곡", artist: null, memo: null, sortOrder: 0, version: 2 }, { title: "수정 곡", sortOrder: 3 })).rejects.toMatchObject({ code: "VERSION_CONFLICT" });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/v1/teams/11/songs/12");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ title: "수정 곡", sortOrder: 3, version: 2 });
    expect(authErrorMessage(new ApiError(409, "LAST_GLOBAL_ADMIN", "raw secret"))).toContain("마지막 활성 전체 관리자");
  });
});

describe("roster-bound invitation issuance", () => {
  const original: Invitation = { id: "51", rosterEntryId: "17", usedBy: null, recipientName: "동명이인", status: "AVAILABLE", createdAt: "", expiresAt: "", usedAt: null, revokedAt: null };
  it("issues by explicit roster ID and retains the original roster during reissue", () => {
    const selected = { id: "18", name: "동명이인" };
    expect(invitationInput(selected, undefined, "expiration")).toEqual({ rosterEntryId: "18", expiresAt: "expiration" });
    expect(invitationInput(selected, original)).toEqual({ rosterEntryId: "17", replacesInvitationId: "51" });
    expect(invitationInput(selected, { ...original, rosterEntryId: null })).toEqual({ recipientName: "동명이인", replacesInvitationId: "51" });
    expect(() => invitationInput(undefined)).toThrow(TypeError);
  });
});

describe("directory form validation", () => {
  it("counts Unicode characters and rejects controls while allowing memo line breaks", () => {
    expect(nameError("💛".repeat(40), 40, "이름")).toBeNull();
    expect(nameError("💛".repeat(41), 40, "이름")).not.toBeNull();
    expect(nameError("가상\u0085회원", 40, "이름")).not.toBeNull();
    expect(songError("곡", "가수", "첫 줄\n둘째 줄\t메모", "0")).toBeNull();
    expect(songError("곡", "가수", "메모\0중간", "0")).not.toBeNull();
    for (const order of ["-1", "1.5", "1e2", "2147483648"]) expect(songError("곡", "", "", order)).not.toBeNull();
    expect(songError("곡", "", "", "")).toBeNull();
  });
});
