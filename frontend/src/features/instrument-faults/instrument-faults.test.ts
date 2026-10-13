import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import InstrumentFaultsPage from "@/app/admin/instrument-faults/page";
import MorePage from "@/app/more/page";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey, replaceSessionCache } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { directoryScope, pruneDirectoryCache } from "@/features/directory/queries";
import type { Instrument } from "@/features/reservations/types";
import { ApiError } from "@/lib/api/client";
import { IdempotentRequestState } from "@/lib/api/idempotent-request-state";
import { getInstrumentFaults, getInstruments, submitFaultIntent } from "./api";
import { FaultEditor } from "./editor";
import { faultErrorMessage, staleFaultReloadNotice } from "./errors";
import { faultInputError, faultIntent, faultSelectionChanged } from "./input";
import { currentFaultsQuery, faultHistoryQuery, instrumentsQuery, refreshInstrumentFaults } from "./queries";
import { FaultHistoryCard, InstrumentStatusCard } from "./view";
import { instrumentCodes, type FaultEditorSelection, type FaultIntent, type InstrumentFault } from "./types";

const meta = { serverTime: "2026-10-13T15:00:00+09:00", timeZone: "Asia/Seoul" };
const admin: Member = { id: "fault-test-admin", loginId: "fault-test-admin", displayName: "가상 관리자", status: "ACTIVE", globalRole: "GLOBAL_ADMIN", teamMemberships: [] };
const instrument: Instrument = { code: "DRUMS", name: "드럼", status: "NORMAL", version: 3 };
const incident: InstrumentFault = { id: "fault-test-41", instrumentCode: "DRUMS", instrumentName: "드럼", status: "OPEN", description: "가상 테스트 고장", origin: "ADMIN", reportedByUserId: admin.id, reportedAt: meta.serverTime, resolvedByUserId: null, resolvedAt: null, resolutionNote: null, version: 0, assetVersion: 4 };
const reported: FaultIntent = { kind: "REPORT", input: { instrumentCode: "DRUMS", expectedAssetVersion: 3, description: incident.description } };
const resolved: InstrumentFault = { ...incident, status: "RESOLVED", version: 1, assetVersion: 5, resolvedByUserId: admin.id, resolvedAt: "2026-10-13T15:30:00+09:00", resolutionNote: "가상 복구" };
function renderPage(member: Member | null, fill?: (client: QueryClient) => void, page = InstrumentFaultsPage) {
  const client = new QueryClient(); client.setQueryData(memberQueryKey, member); fill?.(client);
  const result = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(page))));
  client.clear(); return result;
}
function fillManager(client: QueryClient) {
  const scope = directoryScope(admin);
  client.setQueryData(instrumentsQuery(scope).queryKey, [instrument]);
  client.setQueryData(currentFaultsQuery(scope).queryKey, []);
  client.setQueryData(faultHistoryQuery(scope).queryKey, { pages: [{ serverTime: meta.serverTime, incidents: [resolved], nextBeforeId: null }], pageParams: [null] });
}

describe("instrument fault HTTP contract and exact writes", () => {
  it("reads six-asset status and cursor-filtered administrator history without a write key", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => Response.json({ data: path === "/api/v1/instruments" ? [instrument] : { serverTime: meta.serverTime, incidents: [incident], nextBeforeId: "older-40" }, meta }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await getInstruments()).toEqual([instrument]);
    expect(await getInstrumentFaults({ instrumentCode: "DRUMS", beforeId: "41 /?", limit: 20 })).toMatchObject({ incidents: [incident], nextBeforeId: "older-40" });
    expect(fetchMock.mock.calls[1][0]).toBe("/api/v1/instrument-faults?instrumentCode=DRUMS&beforeId=41+%2F%3F&limit=20");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).has("Idempotency-Key")).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it("registers and resolves with captured asset/incident versions, CSRF and supplied stable keys", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => Response.json({ data: path.endsWith("/csrf") ? { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" } : path.endsWith("/resolve") ? resolved : incident, meta }));
    vi.stubGlobal("fetch", fetchMock);
    await submitFaultIntent(reported, "fixture-report-key");
    const intent: FaultIntent = { kind: "RESOLVE", incidentId: incident.id, input: { expectedVersion: 0, expectedAssetVersion: 4, resolutionNote: "가상 복구" } };
    await submitFaultIntent(intent, "fixture-resolve-key");
    expect(fetchMock.mock.calls[1][0]).toBe("/api/v1/instrument-faults");
    expect(fetchMock.mock.calls[3][0]).toBe(`/api/v1/instrument-faults/${incident.id}/resolve`);
    for (const [index, input, key] of [[1, reported.input, "fixture-report-key"], [3, intent.input, "fixture-resolve-key"]] as const) {
      expect(fetchMock.mock.calls[index][1].method).toBe("POST"); expect(JSON.parse(fetchMock.mock.calls[index][1].body)).toEqual(input);
      expect(new Headers(fetchMock.mock.calls[index][1].headers).get("Idempotency-Key")).toBe(key);
      expect(new Headers(fetchMock.mock.calls[index][1].headers).get("X-CSRF-TOKEN")).toBe("fixture-csrf");
    }
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });
  it("treats malformed or unrelated successful writes as uncertain, retaining their original body/key", async () => {
    for (const malformed of [null, {}, { ...incident, version: -1 }, { ...incident, instrumentCode: "BASS" }, { ...incident, reportedAt: "bad" }]) {
      vi.stubGlobal("fetch", vi.fn().mockImplementation(async (path: string) => Response.json({ data: path.endsWith("/csrf") ? { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" } : malformed, meta })));
      const state = new IdempotentRequestState<FaultIntent>(() => "fixture-stable-key"); state.prepare(reported);
      const error = await submitFaultIntent(reported, "fixture-stable-key").catch((error: unknown) => error);
      expect(error).toBeInstanceOf(ApiError); expect(state.failed(error)).toBe(true); expect(state.request).toEqual({ key: "fixture-stable-key", intent: reported });
    }
  });
  it("keeps an uncertain request across offline/CSRF preparation failures and blocks other writes", () => {
    const state = new IdempotentRequestState<FaultIntent>(() => "fixture-stable-key"); const original = structuredClone(reported);
    const editable = structuredClone(reported); state.prepare(editable); editable.input.description = "changed";
    expect(state.failed(new TypeError("lost success response"))).toBe(true);
    for (const [status, code] of [[0, "OFFLINE"], [403, "CSRF_INVALID"], [200, "INVALID_CSRF_RESPONSE"], [429, "TOO_MANY_REQUESTS"]] as const) expect(state.failed(new ApiError(status, code, ""))).toBe(true);
    expect(state.prepare({ kind: "RESOLVE", incidentId: incident.id, input: { expectedVersion: 0, expectedAssetVersion: 4 } })).toBeUndefined();
    expect(state.request).toEqual({ key: "fixture-stable-key", intent: original });
    expect(state.failed(new ApiError(409, "VERSION_CONFLICT", ""))).toBe(false); expect(state.request).toBeNull();
  });
  it("does not fetch CSRF or enqueue a new write while offline", async () => {
    const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); vi.stubGlobal("navigator", { onLine: false });
    await expect(submitFaultIntent(reported, "fixture-offline")).rejects.toMatchObject({ code: "OFFLINE" });
    expect(fetchMock).not.toHaveBeenCalled();
    const state = new IdempotentRequestState<FaultIntent>(() => "fixture-offline"); state.prepare(reported); expect(state.failed(new ApiError(0, "OFFLINE", ""))).toBe(false); expect(state.request).toBeNull();
  });
  it("locates an older open fault by each instrument rather than relying on the global first page", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => Response.json({ data: { serverTime: meta.serverTime, incidents: path.includes("instrumentCode=DRUMS") ? [incident] : [], nextBeforeId: null }, meta }));
    vi.stubGlobal("fetch", fetchMock);
    const query = currentFaultsQuery("fixture-scope"); const client = new QueryClient();
    expect(await client.fetchQuery(query)).toEqual([incident]);
    expect(fetchMock).toHaveBeenCalledTimes(6);
    for (const code of instrumentCodes) expect(fetchMock.mock.calls.some(([path]) => String(path).includes(`instrumentCode=${code}&limit=1`))).toBe(true);
    client.clear();
  });
});

describe("fault input confirmation and stale snapshots", () => {
  const report: FaultEditorSelection = { kind: "REPORT", instrument };
  const resolve: FaultEditorSelection = { kind: "RESOLVE", instrument: { ...instrument, status: "FAULTY", version: 4 }, incident };
  it("requires a trimmed report and bounded plain text, while keeping resolve notes optional", () => {
    expect(faultInputError(report, " \n ")).toContain("사유"); expect(faultInputError(report, " a ")).toBeNull();
    expect(faultInputError(report, "가".repeat(1000))).toBeNull(); expect(faultInputError(report, "가".repeat(1001))).toContain("1000");
    expect(faultInputError(resolve, " ")).toBeNull(); expect(faultInputError(resolve, "a".repeat(1001))).toContain("1000");
  });
  it("uses the exact selected instrument and incident versions rather than the acting administrator", () => {
    expect(faultIntent(report, `  ${incident.description}  `)).toEqual(reported);
    expect(faultIntent(resolve, " ")).toEqual({ kind: "RESOLVE", incidentId: incident.id, input: { expectedVersion: 0, expectedAssetVersion: 4 } });
    expect(faultIntent(resolve, " 가상 복구 ")).toEqual({ kind: "RESOLVE", incidentId: incident.id, input: { expectedVersion: 0, expectedAssetVersion: 4, resolutionNote: "가상 복구" } });
  });
  it("blocks confirmations when current status or incident changed, without treating an uncertain write as failed", () => {
    expect(faultSelectionChanged(report, [instrument], [])).toBe(false); expect(faultSelectionChanged(report, [{ ...instrument, version: 4 }], [])).toBe(true);
    expect(faultSelectionChanged(resolve, [resolve.instrument], [incident])).toBe(false);
    expect(faultSelectionChanged(resolve, [resolve.instrument], [])).toBe(true); expect(faultSelectionChanged(resolve, [resolve.instrument], [{ ...incident, version: 1 }])).toBe(true);
    for (const code of ["VERSION_CONFLICT", "INSTRUMENT_ALREADY_FAULTY", "INSTRUMENT_FAULT_RESOLVED"]) {
      const error = new ApiError(409, code, "private echoed secret"); expect(staleFaultReloadNotice(error, false)).toContain("다시"); expect(staleFaultReloadNotice(error, true)).toBeNull(); expect(faultErrorMessage(error)).not.toContain("secret");
    }
  });
  it("renders accessible confirmation entry and blocks stale editors while preserving existing-booking guidance", () => {
    const markup = renderToStaticMarkup(createElement(FaultEditor, { selection: report, disabled: false, stale: true, uncertain: false, onApply: vi.fn(), onClose: vi.fn() }));
    expect(markup).toContain('id="fault-note"'); expect(markup).toContain('aria-describedby="fault-note-hint"'); expect(markup).toContain('maxLength="1000"');
    expect(markup).toContain("기존 예약·점유·한도와 대기 교환은 그대로 유지"); expect(markup).toContain("악기 상태가 먼저 변경"); expect(markup.match(/<textarea[^>]*>/)?.[0]).toContain("disabled");
    const optional = renderToStaticMarkup(createElement(FaultEditor, { selection: resolve, disabled: false, stale: false, uncertain: false, onApply: vi.fn(), onClose: vi.fn() }));
    expect(optional).toContain("복구 메모 (선택)"); expect(optional.match(/<textarea[^>]*>/)?.[0]).not.toContain("required");
  });
  it("hides a stale warning while a lost success response is being retried, keeping editor writes blocked", () => {
    const intent = faultIntent(resolve, "가상 복구");
    const request = new IdempotentRequestState<FaultIntent>(() => "fixture-lost-resolve"); request.prepare(intent);
    request.failed(new ApiError(502, "REQUEST_FAILED", ""));
    const currentAssets = [{ ...resolve.instrument, status: "NORMAL" as const, version: 5 }];
    const stale = faultSelectionChanged(resolve, currentAssets, []);
    expect(stale).toBe(true);
    const uncertain = renderToStaticMarkup(createElement(FaultEditor, { selection: resolve, disabled: true, stale, uncertain: request.uncertain, onApply: vi.fn(), onClose: vi.fn() }));
    expect(uncertain).not.toContain("악기 상태가 먼저 변경됐어요");
    expect(uncertain.match(/<textarea[^>]*>/)?.[0]).toContain("disabled");
    for (const button of uncertain.match(/<button[^>]*>/g) ?? []) expect(button).toContain("disabled");
    expect(request.request).toEqual({ key: "fixture-lost-resolve", intent });
    expect(request.prepare(reported)).toBeUndefined();
    const external = renderToStaticMarkup(createElement(FaultEditor, { selection: resolve, disabled: false, stale, uncertain: false, onApply: vi.fn(), onClose: vi.fn() }));
    expect(external).toContain("악기 상태가 먼저 변경됐어요");
  });
});

describe("administrator route, history and cache isolation", () => {
  it("never exposes fault data or actions to anonymous, ordinary, inactive or team-leader-only accounts", () => {
    for (const member of [null, { ...admin, globalRole: "MEMBER" as const }, { ...admin, status: "INACTIVE" } as unknown as Member, { ...admin, globalRole: "MEMBER" as const, teamMemberships: [{ teamId: "fixture-team", teamName: "가상 공연조", kind: "REGULAR" as const, role: "LEADER" as const }] }]) {
      const markup = renderPage(member, fillManager); expect(markup).not.toContain(incident.description); expect(markup).not.toContain("드럼 고장 등록"); expect(markup).not.toContain('id="instrument-status-title"');
    }
  });
  it("renders the admin route and both desktop/more links without changing the diary identity", () => {
    const markup = renderPage(admin, fillManager); expect(markup).toContain('href="/admin/instrument-faults"'); expect(markup).toContain("현재 악기 상태"); expect(markup).toContain("드럼 고장 등록"); expect(markup).toContain("복구 완료"); expect(markup).toContain("JEONGEUM · SENTIMENTAL MUSIC");
    const more = renderPage(admin, undefined, MorePage); expect(more).toContain('href="/admin/instrument-faults"'); expect(more).not.toContain("악기 고장 등록·복구</span><span class=\"planned-feature-status\"");
  });
  it("escapes report text, shows owner IDs without fetching a member directory, and labels legacy dates honestly", () => {
    const legacy = { ...incident, origin: "LEGACY" as const, reportedByUserId: null, description: "<script>fixture</script>" };
    const markup = renderToStaticMarkup(createElement(FaultHistoryCard, { incident: legacy }));
    expect(markup).toContain("&lt;script&gt;"); expect(markup).not.toContain("<script>"); expect(markup).toContain("기존 상태 확인 시각"); expect(markup).toContain("원래 등록 시각과 등록자는 확인할 수 없어요");
    expect(renderToStaticMarkup(createElement(FaultHistoryCard, { incident: resolved }))).toContain(`관리 계정 #${admin.id}`);
    const card = renderToStaticMarkup(createElement(InstrumentStatusCard, { instrument: { ...instrument, status: "FAULTY", version: 4 }, incident, disabled: false, onSelect: vi.fn() }));
    expect(card).toContain("기존 예약은 유지"); expect(card).toContain("팀 합주는 고장 안내와 함께 허용"); expect(card).toContain('aria-label="드럼 복구 처리"');
  });
  it("removes old administrator scopes on account, membership or role change, including logout", async () => {
    const client = new QueryClient(); const old = directoryScope(admin); const nextAdmin = { ...admin, id: "other-admin" }; const next = directoryScope(nextAdmin);
    client.setQueryData(instrumentsQuery(old).queryKey, [instrument]); client.setQueryData(currentFaultsQuery(old).queryKey, [incident]);
    client.setQueryData(faultHistoryQuery(old).queryKey, { pages: [], pageParams: [] }); client.setQueryData(instrumentsQuery(next).queryKey, [instrument]);
    await pruneDirectoryCache(client, nextAdmin); expect(client.getQueryData(instrumentsQuery(old).queryKey)).toBeUndefined(); expect(client.getQueryData(currentFaultsQuery(old).queryKey)).toBeUndefined(); expect(client.getQueryData(faultHistoryQuery(old).queryKey)).toBeUndefined(); expect(client.getQueryData(instrumentsQuery(next).queryKey)).toEqual([instrument]);
    await pruneDirectoryCache(client, { ...nextAdmin, globalRole: "MEMBER" }); expect(client.getQueryData(instrumentsQuery(next).queryKey)).toBeUndefined();
    client.setQueryData(currentFaultsQuery(next).queryKey, [incident]); await pruneDirectoryCache(client, { ...nextAdmin, status: "INACTIVE" } as unknown as Member); expect(client.getQueryData(currentFaultsQuery(next).queryKey)).toBeUndefined();
    client.setQueryData(currentFaultsQuery(next).queryKey, [incident]); await replaceSessionCache(client, null); expect(client.getQueryData(currentFaultsQuery(next).queryKey)).toBeUndefined(); client.clear();
  });
  it("refreshes booking, exchange, recurring and session status after a fault write", async () => {
    const client = new QueryClient();
    const keys = [instrumentsQuery(directoryScope(admin)).queryKey, ["reservations", "scope"], ["exchanges", "scope"], ["admin", "recurring", "scope"], memberQueryKey];
    for (const key of keys) client.setQueryData(key, []);
    await refreshInstrumentFaults(client); for (const key of keys) expect(client.getQueryState(key)?.isInvalidated).toBe(true); client.clear();
  });
});
