import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import MembersPage from "@/app/admin/members/page";
import TeamPage from "@/app/teams/[id]/page";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { directoryScope, membersQuery, pruneDirectoryCache, rosterQuery, teamQuery } from "@/features/directory/queries";
import { TeamDetailView } from "@/features/directory/team-detail";
import type { AdminMember, TeamDetail } from "@/features/directory/types";
import { cleanupCurrentPushDevice } from "@/features/push/browser";
import { ApiError } from "@/lib/api/client";
import { IdempotentRequestState } from "@/lib/api/idempotent-request-state";
import { submitLifecycleIntent, validLifecycleResult } from "./api";
import { LifecycleConfirmation, lifecycleResultMessage, UncertainLifecycle } from "./confirmation";
import { lifecycleErrorMessage } from "./errors";
import { disbandBlock, lifecycleIntent, lifecycleReasonError, lifecycleTargetChanged, withdrawalBlock } from "./input";
import { refreshLifecycle } from "./queries";
import { finishSelfWithdrawal } from "./self-withdrawal";
import { adminTeamPlaceholder, teamLifecycleIdentity, teamReadDenied } from "./team-state";
import type { LifecycleIntent, LifecycleResult, LifecycleTarget } from "./types";

vi.mock("@/features/push/browser", async (importOriginal) => ({ ...await importOriginal<typeof import("@/features/push/browser")>(), cleanupCurrentPushDevice: vi.fn() }));
vi.mock("next/navigation", () => ({ usePathname: () => "/teams/11", useParams: () => ({ id: "11" }) }));
const meta = { serverTime: "2026-10-14T19:00:00+09:00", timeZone: "Asia/Seoul" };
const admin: Member = { id: "1", loginId: "fixture.admin", displayName: "가상 관리자", status: "ACTIVE", globalRole: "GLOBAL_ADMIN", teamMemberships: [] };
const account: AdminMember = { id: "2", loginId: "fixture.member", displayName: "가상 회원", status: "ACTIVE", globalRole: "MEMBER", rosterEntryId: "32", version: 4 };
const target: LifecycleTarget = { kind: "WITHDRAW", id: account.id, name: account.displayName, version: account.version };
const result: LifecycleResult = { operationId: "8", targetId: account.id, status: "WITHDRAWN", version: 5, canceledReservations: 3, invalidatedExchanges: 2, endedAssignments: 0, deactivatedSubscriptions: 2, revokedSessions: 4 };
const team: TeamDetail = { team: { id: "11", name: "가상 공연조", kind: "REGULAR", status: "ACTIVE", version: 3 }, members: [], songs: [], canViewMembers: true, canManageTeam: true, canManageSongs: true };
function renderPage(page: React.ReactNode, member: Member | null = admin, seed?: (client: QueryClient) => void) {
  const client = new QueryClient(); client.setQueryData(memberQueryKey, member); seed?.(client);
  const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, page)));
  client.clear(); return html;
}
function csrfThen(value: unknown) { return vi.fn().mockImplementation(async (path: string) => Response.json({ data: path.endsWith("/csrf") ? { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" } : value, meta })); }
function confirm(overrides: Partial<Parameters<typeof LifecycleConfirmation>[0]> = {}) {
  return renderToStaticMarkup(createElement(LifecycleConfirmation, { target, stale: false, pending: false, uncertain: false, error: null, onConfirm: vi.fn(), onRetry: vi.fn(), onCancel: vi.fn(), ...overrides }));
}

describe("lifecycle API and explicit response recovery", () => {
  it("captures normalized reason and expected version for withdrawal and disband with CSRF/key", async () => {
    for (const kind of ["WITHDRAW", "DISBAND"] as const) {
      const intent = lifecycleIntent({ ...target, kind }, "  운영 처리 \n ");
      const response = { ...result, status: kind === "WITHDRAW" ? "WITHDRAWN" : "DISBANDED" };
      const fetchMock = csrfThen(response); vi.stubGlobal("fetch", fetchMock);
      expect(await submitLifecycleIntent(intent, "fixture-stable-key")).toEqual(response);
      const call = fetchMock.mock.calls[1];
      expect(call[0]).toBe(kind === "WITHDRAW" ? "/api/v1/users/2/withdraw" : "/api/v1/teams/2/disband");
      expect(call[1].method).toBe("POST"); expect(JSON.parse(call[1].body)).toEqual({ expectedVersion: 4, reason: "운영 처리" });
      expect(new Headers(call[1].headers).get("Idempotency-Key")).toBe("fixture-stable-key"); expect(new Headers(call[1].headers).get("X-CSRF-TOKEN")).toBe("fixture-csrf");
    }
  });
  it("rechecks a lost response with the identical captured body/key, without an automatic retry", async () => {
    let writes = 0; const fetchMock = vi.fn().mockImplementation(async (path: string) => {
      if (path.endsWith("/csrf")) return Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta });
      writes++; if (writes === 1) throw new TypeError("lost after commit"); return Response.json({ data: result, meta });
    }); vi.stubGlobal("fetch", fetchMock);
    const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-original-key");
    const input = lifecycleIntent(target, "처음 사유"); state.prepare(input); input.input.reason = "변경된 사유";
    const error = await submitLifecycleIntent(state.request!.intent, state.request!.key).catch((failure: unknown) => failure);
    expect(state.failed(error)).toBe(true); expect(writes).toBe(1);
    expect(state.prepare(lifecycleIntent({ ...target, id: "99" }, "다른 대상"))).toBeUndefined();
    expect(await submitLifecycleIntent(state.request!.intent, state.request!.key)).toEqual(result);
    const calls = fetchMock.mock.calls.filter(([path]) => path.endsWith("/withdraw")); expect(calls).toHaveLength(2);
    expect(calls[0][1].body).toBe(calls[1][1].body); expect(JSON.parse(calls[1][1].body).reason).toBe("처음 사유");
    expect(new Headers(calls[1][1].headers).get("Idempotency-Key")).toBe("fixture-original-key");
    state.clear(); expect(state.request).toBeNull();
  });
  it("retains uncertainty across lock timeout and failed CSRF preparation until a valid result", () => {
    const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-key"); state.prepare(lifecycleIntent(target, ""));
    expect(state.failed(new ApiError(503, "LIFECYCLE_TEMPORARILY_UNAVAILABLE", "private"))).toBe(true);
    expect(state.failed(new ApiError(403, "CSRF_INVALID", "private"))).toBe(true);
    expect(state.request!.intent.input).toEqual({ expectedVersion: 4 });
    expect(state.failed(new ApiError(409, "VERSION_CONFLICT", "private"))).toBe(false); expect(state.request).toBeNull();
  });
  it("treats malformed or unrelated success as uncertain rather than announcing cleanup", async () => {
    const intent = lifecycleIntent(target, "");
    for (const malformed of [null, {}, { ...result, targetId: "99" }, { ...result, status: "DISBANDED" }, { ...result, version: 4 }, { ...result, operationId: "" }, { ...result, canceledReservations: -1 }, { ...result, revokedSessions: "4" }, { ...result, endedAssignments: 0.5 }]) {
      expect(validLifecycleResult(malformed, intent)).toBe(false); vi.stubGlobal("fetch", csrfThen(malformed));
      const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-key"); state.prepare(intent);
      const error = await submitLifecycleIntent(intent, "fixture-key").catch((failure: unknown) => failure);
      expect(error).toBeInstanceOf(ApiError); expect(state.failed(error)).toBe(true); expect(state.request!.intent).toEqual(intent);
    }
    expect(validLifecycleResult(result, intent)).toBe(true);
  });
  it("does not send or queue offline cleanup, including explicit recovery of an uncertain request", async () => {
    const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); vi.stubGlobal("navigator", { onLine: false });
    const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-key"); state.prepare(lifecycleIntent(target, ""));
    const error = await submitLifecycleIntent(state.request!.intent, state.request!.key).catch((failure: unknown) => failure);
    expect(error).toMatchObject({ code: "OFFLINE" }); expect(state.failed(error)).toBe(false); expect(fetchMock).not.toHaveBeenCalled();
    state.prepare(lifecycleIntent(target, "")); state.failed(new TypeError("lost"));
    expect(state.failed(error)).toBe(true); expect(state.request).not.toBeNull();
  });
  it("releases rejected version, authority and idempotency conflicts for an explicit fresh review", () => {
    for (const [status, code] of [[409, "VERSION_CONFLICT"], [409, "LAST_GLOBAL_ADMIN"], [409, "IDEMPOTENCY_KEY_REUSED"], [403, "FORBIDDEN"], [404, "RESOURCE_NOT_FOUND"]] as const) {
      const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-key"); state.prepare(lifecycleIntent(target, ""));
      expect(state.failed(new ApiError(status, code, "private"))).toBe(false); expect(state.request).toBeNull();
    }
  });
  it("explains a confirmed storage conflict as a fresh review rather than offering an unavailable recheck", () => {
    const failure = new ApiError(409, "DATA_CONFLICT", "private diagnostic");
    const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-key"); state.prepare(lifecycleIntent(target, ""));
    expect(state.failed(failure)).toBe(false); expect(state.request).toBeNull();
    const message = lifecycleErrorMessage(failure); expect(message).toContain("저장 충돌로 처리되지 않았어요"); expect(message).toContain("최신 회원·팀 상태"); expect(message).toContain("직접 다시 시도"); expect(message).not.toContain("같은 요청 결과 다시 확인"); expect(message).not.toContain("diagnostic");
  });
});

describe("cleanup confirmation, state and administrator boundaries", () => {
  it("allows bounded optional reason and snapshots the chosen version without sensitive defaults", () => {
    expect(lifecycleReasonError("가".repeat(500))).toBeNull(); expect(lifecycleReasonError("가".repeat(501))).toContain("500");
    expect(lifecycleIntent(target, "  ")).toEqual({ kind: "WITHDRAW", id: "2", input: { expectedVersion: 4 } });
    expect(lifecycleTargetChanged(target, 4)).toBe(false); expect(lifecycleTargetChanged(target, 5)).toBe(true); expect(lifecycleTargetChanged(target, undefined)).toBe(true); expect(lifecycleTargetChanged(target, 4, false)).toBe(true);
  });
  it("explains preserved team reservations/history, all-device revocation and manual leader appointment", () => {
    const html = confirm({ self: true });
    expect(html).toContain('role="dialog"'); expect(html).toContain('aria-modal="true"'); expect(html).toContain("존속 팀의 예약은 유지"); expect(html).toContain("모든 기기의 Push와 로그인 세션"); expect(html).toContain("후임은 전체 관리자가 직접"); expect(html).toContain("완료된 이용 이력은 보존"); expect(html).toContain("모든 기기에서 로그아웃");
    expect(html.match(/<button[^>]*type="submit"[^>]*>/)?.[0]).toContain("disabled"); expect(html).toContain("예약·권한·알림에 미치는 영향을 확인했어요");
  });
  it("explains disband scope and blocks stale confirmation while preserving uncertain recheck", () => {
    const html = confirm({ target: { ...target, kind: "DISBAND" }, stale: true });
    expect(html).toContain("반복 고정 배정을 종료"); expect(html).toContain("다른 팀과 개인 예약은 유지"); expect(html).toContain("대상 상태가 변경됐거나"); expect(html.match(/<textarea[^>]*>/)?.[0]).toContain("disabled");
    const lost = confirm({ stale: true, uncertain: true }); expect(lost).not.toContain("대상 상태가 변경됐거나"); expect(lost).toContain("같은 요청 결과 다시 확인"); expect(lost).not.toContain("돌아가기</button>"); expect(lost.match(/<textarea[^>]*>/)?.[0]).toContain("disabled");
  });
  it("shows a safe explicit recheck and does not echo untrusted diagnostic messages", () => {
    const html = renderToStaticMarkup(createElement(UncertainLifecycle, { pending: false, onRetry: vi.fn() }));
    expect(html).toContain("처음 확인한 대상·버전·사유"); expect(html).toContain("실제 처리가 완료됐을 수 있어요");
    for (const code of ["VERSION_CONFLICT", "LAST_GLOBAL_ADMIN", "MEMBER_ALREADY_WITHDRAWN", "TEAM_ALREADY_DISBANDED", "IDEMPOTENCY_KEY_REUSED", "LOCK_TIMEOUT", "CSRF_INVALID", "OFFLINE", "FORBIDDEN"]) expect(lifecycleErrorMessage(new ApiError(code === "FORBIDDEN" ? 403 : 409, code, "private secret"))).not.toContain("secret");
    expect(lifecycleErrorMessage(new ApiError(403, "CSRF_INVALID", ""))).toContain("보안 확인"); expect(lifecycleErrorMessage(new ApiError(403, "FORBIDDEN", ""))).toContain("전체 관리자");
  });
  it("blocks the last active administrator and withdrawn accounts, but allows inactive cleanup", () => {
    const onlyAdmin: AdminMember = { ...account, globalRole: "GLOBAL_ADMIN" };
    expect(withdrawalBlock(onlyAdmin, [onlyAdmin, account])).toContain("마지막 활성");
    expect(withdrawalBlock(onlyAdmin, [onlyAdmin, { ...onlyAdmin, id: "3" }])).toBeNull();
    expect(withdrawalBlock({ ...onlyAdmin, status: "INACTIVE" }, [onlyAdmin])).toBeNull();
    expect(withdrawalBlock({ ...account, status: "WITHDRAWN" }, [account])).toContain("이미 탈퇴");
    expect(disbandBlock(team.team)).toBeNull(); expect(disbandBlock({ ...team.team, status: "DISBANDED" })).toContain("이미 해체");
  });
  it("shows administrator-only withdrawal and last-admin protection without exposing controls to members", () => {
    const html = renderPage(createElement(MembersPage), admin, (client) => {
      client.setQueryData(membersQuery(admin.id).queryKey, [{ ...account, id: admin.id, globalRole: "GLOBAL_ADMIN" }, account]);
      client.setQueryData(rosterQuery(admin.id).queryKey, []);
    });
    expect(html).toContain("탈퇴 처리</button>"); expect(html).toContain("마지막 활성 전체 관리자"); expect(html).toContain("존속 팀의 예약과 완료 이력"); expect(html).toContain("JEONGEUM · SENTIMENTAL MUSIC");
    for (const member of [null, { ...admin, globalRole: "MEMBER" as const }]) expect(renderPage(createElement(MembersPage), member)).not.toContain("탈퇴 처리</button>");
  });
  it("never grants disband permission to a leader or an accidental management payload", () => {
    const leader: Member = { ...admin, globalRole: "MEMBER", teamMemberships: [{ teamId: team.team.id, teamName: team.team.name, kind: "REGULAR", role: "LEADER" }] };
    expect(renderPage(createElement(TeamDetailView, { detail: team }), leader)).not.toContain("팀 해체 확인</button>");
    expect(renderPage(createElement(TeamDetailView, { detail: team }), admin)).toContain("팀 해체 확인</button>");
    const ended = renderPage(createElement(TeamDetailView, { detail: { ...team, team: { ...team.team, status: "DISBANDED" } } }), admin);
    expect(ended).toContain("이미 해체된 팀"); expect(ended).not.toContain('id="edit-team-name"'); expect(ended).not.toContain('id="new-song-title"');
  });
  it("retains cached admin detail without writable controls during a temporary read failure", () => {
    const html = renderPage(createElement(TeamPage), admin, (client) => {
      const key = teamQuery(directoryScope(admin), "11").queryKey; client.setQueryData(key, team);
      client.getQueryCache().find({ queryKey: key })!.setState({ status: "error", error: new ApiError(503, "REQUEST_FAILED", "private") });
    });
    expect(html).toContain(team.team.name);
    expect(html.match(/<button[^>]*>팀 해체 확인<\/button>/)?.[0]).toContain("disabled");
    expect(html.match(/<input[^>]*id="edit-team-name"[^>]*>/)?.[0]).toContain("disabled");
  });
  it("keeps a lost disband request unchanged when the acting administrator's team membership disappears", async () => {
    const before: Member = { ...admin, teamMemberships: [{ teamId: team.team.id, teamName: team.team.name, kind: "REGULAR", role: "LEADER" }] };
    const after: Member = { ...before, teamMemberships: [] };
    const oldKey = teamQuery(directoryScope(before), team.team.id).queryKey;
    expect(directoryScope(before)).not.toBe(directoryScope(after));
    expect(teamLifecycleIdentity(before, team.team.id)).toBe(teamLifecycleIdentity(after, team.team.id));
    const state = new IdempotentRequestState<LifecycleIntent>(() => "fixture-disband-original-key");
    const captured = lifecycleIntent({ kind: "DISBAND", id: team.team.id, name: team.team.name, version: team.team.version }, "처음 해체 사유");
    state.prepare(captured); state.failed(new ApiError(502, "REQUEST_FAILED", "lost committed response"));
    expect(adminTeamPlaceholder(team, oldKey, after, team.team.id)).toBe(team);
    const client = new QueryClient(); client.setQueryData(oldKey, team); await pruneDirectoryCache(client, after);
    expect(client.getQueryData(oldKey)).toBeUndefined();
    expect(state.request).toEqual({ key: "fixture-disband-original-key", intent: captured });
    expect(teamReadDenied(new TypeError("new-scope GET failed"))).toBe(false);
    expect(state.prepare({ ...captured, input: { expectedVersion: 4, reason: "다른 사유" } })).toBeUndefined();
    const committed = { ...result, targetId: team.team.id, status: "DISBANDED", version: 4 };
    const fetchMock = csrfThen(committed); vi.stubGlobal("fetch", fetchMock); expect(fetchMock).not.toHaveBeenCalled();
    expect(await submitLifecycleIntent(state.request!.intent, state.request!.key)).toEqual(committed);
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ expectedVersion: 3, reason: "처음 해체 사유" });
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("fixture-disband-original-key"); client.clear();
  });
  it("rejects placeholder data and action identity reuse across accounts, roles and targets", () => {
    const key = teamQuery(directoryScope(admin), team.team.id).queryKey;
    for (const member of [{ ...admin, id: "9" }, { ...admin, globalRole: "MEMBER" as const }]) {
      expect(adminTeamPlaceholder(team, key, member, team.team.id)).toBeUndefined();
      expect(teamLifecycleIdentity(member, team.team.id)).not.toBe(teamLifecycleIdentity(admin, team.team.id));
    }
    expect(adminTeamPlaceholder(team, key, admin, "other-team")).toBeUndefined();
    expect(adminTeamPlaceholder(team, ["directory", "invalid-json", "team", team.team.id], admin, team.team.id)).toBeUndefined();
    expect(adminTeamPlaceholder(team, ["directory", directoryScope(admin), "teams"], admin, team.team.id)).toBeUndefined();
    for (const status of [401, 403, 404]) expect(teamReadDenied(new ApiError(status, "REQUEST_FAILED", ""))).toBe(true);
  });
  it("hides cached detail after access/not-found denial and for a member's failed read", () => {
    for (const [member, error] of [
      [admin, new ApiError(401, "UNAUTHORIZED", "")], [admin, new ApiError(403, "FORBIDDEN", "")], [admin, new ApiError(404, "RESOURCE_NOT_FOUND", "")],
      [{ ...admin, globalRole: "MEMBER" as const }, new TypeError("lost")],
    ] as const) {
      const html = renderPage(createElement(TeamPage), member, (client) => {
        const key = teamQuery(directoryScope(member), "11").queryKey; client.setQueryData(key, team);
        client.getQueryCache().find({ queryKey: key })!.setState({ status: "error", error });
      });
      expect(html).not.toContain(team.team.name); expect(html).not.toContain("팀 해체 확인</button>");
    }
  });
  it("refreshes reservations, exchanges, inbox, devices, recurring/admin state and current authority", async () => {
    const client = new QueryClient(); const scope = directoryScope(admin);
    const keys = [["admin", "recurring", scope], ["directory", scope], ["reservations", scope], ["exchanges", scope], ["notifications", scope], ["push", scope], memberQueryKey];
    keys.forEach((key) => client.setQueryData(key, [])); await refreshLifecycle(client);
    keys.forEach((key) => expect(client.getQueryState(key)?.isInvalidated).toBe(true)); client.clear();
    expect(lifecycleResultMessage(result)).toContain("예약 취소 3건"); expect(lifecycleResultMessage(result)).toContain("세션 해제 4건");
  });
  it("clears a confirmed self-withdrawal immediately even if browser Push cleanup hangs", async () => {
    let resolve!: (value: { browserCleanupComplete: boolean }) => void;
    vi.mocked(cleanupCurrentPushDevice).mockImplementation(() => new Promise((done) => { resolve = done; }));
    const clear = vi.fn().mockResolvedValue(undefined), navigate = vi.fn();
    const completion = finishSelfWithdrawal(clear, navigate); await Promise.resolve(); await Promise.resolve();
    expect(clear).toHaveBeenCalledTimes(1); expect(navigate).toHaveBeenCalledTimes(1); expect(cleanupCurrentPushDevice).toHaveBeenCalledTimes(1);
    resolve({ browserCleanupComplete: false }); expect(await completion).toEqual({ browserCleanupComplete: false });
  });
});
