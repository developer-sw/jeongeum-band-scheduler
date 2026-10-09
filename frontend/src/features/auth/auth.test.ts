import { QueryClient, QueryObserver } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import { invalidatesSession, memberAccess } from "./access";
import { login, issueInvitation } from "./api";
import { authErrorMessage } from "./errors";
import { memberQueryKey, replaceSessionCache } from "./session-cache";
import { displayNameError, loginIdError, normalizeLoginId, passwordError } from "./validation";
import type { Member } from "./types";

const member: Member = { id: "test-member", loginId: "member-test", displayName: "테스트 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [] };
const meta = { serverTime: "2026-10-09T00:00:00+09:00", timeZone: "Asia/Seoul" };
afterEach(() => vi.unstubAllGlobals());

describe("authentication boundaries", () => {
  it("notifies the mounted member observer immediately on logout and account replacement", async () => {
    const client = new QueryClient();
    client.setQueryData(memberQueryKey, member);
    const originalQuery = client.getQueryCache().find({ queryKey: memberQueryKey });
    const observer = new QueryObserver<Member | null>(client, { queryKey: memberQueryKey, enabled: false });
    const unsubscribe = observer.subscribe(vi.fn());
    await replaceSessionCache(client, null);
    expect(observer.getCurrentResult()).toMatchObject({ status: "success", data: null });
    await replaceSessionCache(client, { ...member, id: "next-account" });
    expect(observer.getCurrentResult().data?.id).toBe("next-account");
    expect(client.getQueryCache().find({ queryKey: memberQueryKey })).toBe(originalQuery);
    unsubscribe(); client.clear();
  });
  it("allows active members to view their account and restricts invitations to global administrators", () => {
    expect(memberAccess(null, true)).toBe("LOGIN_REQUIRED");
    expect(memberAccess(member)).toBe("ALLOWED");
    expect(memberAccess(member, true)).toBe("FORBIDDEN");
    expect(memberAccess({ ...member, globalRole: "GLOBAL_ADMIN" }, true)).toBe("ALLOWED");
    expect(memberAccess({ ...member, status: "INACTIVE" } as unknown as Member)).toBe("LOGIN_REQUIRED");
  });

  it("invalidates a stale session while keeping an ordinary failed-login message separate", () => {
    expect(invalidatesSession(new ApiError(401, "AUTHENTICATION_REQUIRED", ""))).toBe(true);
    expect(invalidatesSession(new ApiError(403, "INACTIVE_USER", ""))).toBe(true);
    expect(invalidatesSession(new ApiError(401, "INVALID_CREDENTIALS", ""))).toBe(false);
  });

  it("cancels a previous user's in-flight query before account replacement so late data cannot repopulate the cache", async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    let complete!: (value: string) => void;
    let previousSignal: AbortSignal | undefined;
    const previous = client.fetchQuery({ queryKey: ["private", "previous"], queryFn: ({ signal }) => { previousSignal = signal; return new Promise<string>((resolve) => { complete = resolve; }); } }).catch(() => undefined);
    client.setQueryData(["private", "cached"], { name: "previous user's data" });

    await replaceSessionCache(client, member);
    complete("late previous-user response");
    await previous;

    expect(previousSignal?.aborted).toBe(true);
    expect(client.getQueryData(["private", "previous"])).toBeUndefined();
    expect(client.getQueryData(["private", "cached"])).toBeUndefined();
    expect(client.getQueryData(memberQueryKey)).toEqual(member);
    client.clear();
  });

  it("sends credentials and newly issued tokens only in transient API requests, outside both Query caches", async () => {
    const client = new QueryClient();
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "csrf-test" }, meta }))
      .mockResolvedValueOnce(Response.json({ data: member, meta }))
      .mockResolvedValueOnce(Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "csrf-next" }, meta }))
      .mockResolvedValueOnce(Response.json({ data: { id: "invitation-test", recipientName: "테스트 대상", token: "invitation-secret-test" }, meta }));
    vi.stubGlobal("fetch", fetchMock);
    await login({ loginId: "member-test", password: "password-secret-test" });
    const issued = await issueInvitation({ recipientName: "테스트 대상" });
    expect(issued.token).toBe("invitation-secret-test");
    expect(fetchMock.mock.calls.map(([path]) => path).join(" ")).not.toContain("secret");
    expect(client.getQueryCache().getAll()).toEqual([]);
    expect(client.getMutationCache().getAll()).toEqual([]);
    client.clear();
  });

  it("uses curated messages rather than rendering an arbitrary echoed password or token", () => {
    expect(authErrorMessage(new ApiError(500, "UNKNOWN", "password-secret-test"))).not.toContain("secret");
    expect(authErrorMessage(new ApiError(410, "INVITATION_UNAVAILABLE", "invitation-secret-test"))).toContain("새 초대");
  });
});

describe("input validation matches the Java contract", () => {
  it("normalizes login IDs and requires the first character to be alphanumeric", () => {
    expect(normalizeLoginId(" Member.Test ")).toBe("member.test");
    expect(loginIdError("Member.Test")).toBeUndefined();
    expect(loginIdError(".member")).toBeDefined();
    expect(loginIdError("한글아이디")).toBeDefined();
  });

  it("counts Unicode code points and separately enforces UTF-8 password byte limits", () => {
    expect(passwordError("💛".repeat(6))).toBeDefined();
    expect(passwordError("💛".repeat(12))).toBeUndefined();
    expect(passwordError("💛".repeat(19))).toBeDefined();
    expect(passwordError("abcdefghijk\0")).toBeDefined();
    expect(displayNameError("💛".repeat(40))).toBeUndefined();
    expect(displayNameError("💛".repeat(41))).toBeDefined();
    expect(displayNameError("name\u0085")).toBeDefined();
  });
});
