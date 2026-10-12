import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Member } from "@/features/auth/types";
import { memberQueryKey, replaceSessionCache } from "@/features/auth/session-cache";
import { directoryScope, pruneDirectoryCache, refreshDirectory } from "@/features/directory/queries";
import { myReservationsQuery, refreshReservations, reservationQuery, scheduleQuery } from "@/features/reservations/queries";
import type { InstrumentCode, ReservationDetail } from "@/features/reservations/types";
import { ApiError } from "@/lib/api/client";
import { IdempotentRequestState } from "@/lib/api/idempotent-request-state";
import { checkedExchange, getExchangeCandidates, submitExchangeIntent } from "./api";
import { CreateExchangeForm, ExchangeArrival } from "./create-form";
import { ExchangeDetail } from "./detail";
import { exchangeErrorMessage } from "./errors";
import { exchangeCandidatesQuery, exchangeQuery, exchangesQuery, refreshExchanges } from "./queries";
import { allocationInput, createExchangeInput, groupError, pendingExchange } from "./selection";
import type { ExchangeCandidate, ExchangeIntent, ExchangeResponse } from "./types";

const date = "2026-10-12"; const now = Date.parse(`${date}T11:00:00+09:00`);
const meta = { serverTime: `${date}T11:00:00+09:00`, timeZone: "Asia/Seoul" };
const member: Member = { id: "501", loginId: "synthetic.exchange", displayName: "가상 교환 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [{ teamId: "71", teamName: "가상 정규조", kind: "REGULAR", role: "MEMBER" }] };
const source: ReservationDetail = { id: "9007199254740994", version: 7, subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, instrumentCodes: ["DRUMS"], status: "CONFIRMED", phase: "FUTURE", originKind: "MANUAL", quotaExempt: false, isMine: true, canCancel: true, canChange: true, ownerUserId: member.id, creatorUserId: member.id, date, blockCode: "B01", startsAt: `${date}T12:00:00+09:00`, endsAt: `${date}T13:00:00+09:00`, usableFrom: `${date}T12:00:00+09:00`, quotaMinutes: 60, teamFaultWarnings: [], canceledAt: null, cancelReason: null, successorIds: [], dualAssetException: false };
const candidate: ExchangeCandidate = { id: source.id, version: source.version, subjectType: source.subjectType, purpose: source.purpose, teamId: null, teamName: null, instrumentCodes: source.instrumentCodes, status: source.status, phase: source.phase, originKind: source.originKind, quotaExempt: false, isMine: true, canCancel: true, canChange: true, date, blockCode: source.blockCode, startsAt: source.startsAt, endsAt: source.endsAt, quotaMinutes: 60, dualAssetException: false, bundleKey: "opaque-same-owner-creator" };
const second: ExchangeCandidate = { ...candidate, id: "9007199254740995", blockCode: "B02", startsAt: `${date}T13:00:00+09:00`, endsAt: `${date}T14:00:00+09:00`, instrumentCodes: ["KEYBOARD"] };
const ninety: ExchangeCandidate = { ...candidate, id: "9007199254740996", blockCode: "B07", startsAt: `${date}T18:00:00+09:00`, endsAt: `${date}T19:30:00+09:00`, quotaMinutes: 0, bundleKey: "opaque-other-owner", canChange: false, canCancel: false, isMine: false };
const counterpart: ReservationDetail = { ...source, ...ninety, ownerUserId: "502", creatorUserId: "502" };
const request: ExchangeResponse = { id: "901", version: 0, status: "PENDING", requesterUserId: member.id, expiresAt: source.startsAt, createdAt: meta.serverTime, processedAt: null, processedByUserId: null, stateReason: null, serverTime: meta.serverTime, offered: [source], requested: [counterpart], preview: [
  { side: "REQUESTER", sourceReservationIds: [source.id], subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, ownerUserId: member.id, creatorUserId: member.id, date, blockCode: "B07", instrumentCodes: ["DRUMS"], allocationRequired: false, quotaMinutes: 0, teamFaultWarnings: [] },
  { side: "COUNTERPART", sourceReservationIds: [counterpart.id], subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, ownerUserId: "502", creatorUserId: "502", date, blockCode: "B01", instrumentCodes: ["DRUMS"], allocationRequired: false, quotaMinutes: 60, teamFaultWarnings: [] },
], allocationReservationIds: [], canAllocateReservationIds: [], canApprove: false, canReject: false, canCancel: true, replacedReservationIds: [], reservations: [] };
const createIntent: ExchangeIntent = { kind: "CREATE", input: { offered: [{ reservationId: source.id, expectedVersion: 7 }], requested: [{ reservationId: counterpart.id, expectedVersion: 7 }] } };
afterEach(() => vi.unstubAllGlobals());

describe("exchange structure and source eligibility", () => {
  it("supports single 60↔90 and two adjacent 60↔90 in either direction without rounding string IDs", () => {
    expect(createExchangeInput([candidate], [ninety], now)).toEqual(createIntent.input);
    expect(createExchangeInput([second, candidate], [ninety], now)).toMatchObject({ offered: [{ reservationId: candidate.id }, { reservationId: second.id }] });
    expect(createExchangeInput([{ ...ninety, canChange: true }], [candidate, second], now)).toMatchObject({ requested: [{ reservationId: candidate.id }, { reservationId: second.id }] });
  });
  it("rejects nonadjacent, different dates, creator/owner bundles, non60 pairs and duplicate sources", () => {
    for (const invalid of [{ ...second, startsAt: `${date}T14:00:00+09:00`, endsAt: `${date}T15:00:00+09:00` }, { ...second, date: "2026-10-13" }, { ...second, bundleKey: "other-creator" }, { ...second, endsAt: `${date}T14:30:00+09:00` }]) expect(groupError([candidate, invalid], now, true)).toContain("같은 날짜");
    expect(groupError([candidate, candidate], now, true)).toContain("중복");
    expect(createExchangeInput([candidate, second], [{ ...ninety, id: "third", endsAt: `${date}T19:00:00+09:00` }], now)).toContain("90분 한 건");
  });
  it("does not allow 2↔2, repeated IDs across sides, or a missing/started/stale/uneditable offered reservation", () => {
    expect(createExchangeInput([candidate, second], [{ ...candidate, id: "third" }, { ...second, id: "fourth" }], now)).toContain("두 건끼리");
    expect(createExchangeInput([candidate], [candidate], now)).toContain("양쪽");
    expect(createExchangeInput([], [ninety], now)).toContain("선택");
    expect(createExchangeInput([{ ...candidate, canChange: false }], [ninety], now)).toContain("변경 권한");
    expect(createExchangeInput([{ ...candidate, status: "REPLACED" }], [ninety], now)).toContain("확정 예약");
    expect(createExchangeInput([candidate], [ninety], Date.parse(source.startsAt))).toContain("시작하지 않은");
  });
  it("preserves both assets in 90→two60 while forbidding automatic dual90→single60 reduction", () => {
    const dual = { ...ninety, canChange: true, dualAssetException: true, instrumentCodes: ["DRUMS", "KEYBOARD"] as InstrumentCode[] };
    expect(typeof createExchangeInput([dual], [candidate, second], now)).toBe("object");
    expect(createExchangeInput([dual], [candidate], now)).toContain("악기를 유지");
    expect(createExchangeInput([candidate], [dual], now)).toContain("악기를 유지");
  });
  it("requires the source owner and server allocation permission, even for global administrators", () => {
    const dualSource = { ...source, ...ninety, ownerUserId: member.id, instrumentCodes: ["DRUMS", "KEYBOARD"] as InstrumentCode[], dualAssetException: true };
    const dualRequest = { ...request, offered: [dualSource], allocationReservationIds: [dualSource.id], canAllocateReservationIds: [dualSource.id] };
    expect(allocationInput(dualRequest, dualSource.id, ["KEYBOARD", "DRUMS"], member.id, now)).toMatchObject({ expectedVersion: 0, instrumentCodesOrder: ["KEYBOARD", "DRUMS"] });
    for (const order of [["DRUMS", "DRUMS"], ["DRUMS", "BASS"], ["DRUMS"]] as InstrumentCode[][]) expect(typeof allocationInput(dualRequest, dualSource.id, order, member.id, now)).toBe("string");
    expect(typeof allocationInput(dualRequest, dualSource.id, ["DRUMS", "KEYBOARD"], "admin-id", now)).toBe("string");
    expect(typeof allocationInput({ ...dualRequest, canAllocateReservationIds: [] }, dualSource.id, ["DRUMS", "KEYBOARD"], member.id, now)).toBe("string");
    expect(typeof allocationInput(dualRequest, dualSource.id, ["DRUMS", "KEYBOARD"], member.id, Date.parse(dualRequest.expiresAt))).toBe("string");
  });
});

describe("exchange API identity, uncertainty and offline behavior", () => {
  it("fetches minimal candidates by window without using private reservation detail routes", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ data: { serverTime: meta.serverTime, reservations: [candidate] }, meta })); vi.stubGlobal("fetch", fetchMock);
    expect((await getExchangeCandidates(date, "2026-10-26")).reservations[0]).not.toHaveProperty("ownerUserId");
    expect(fetchMock.mock.calls[0][0]).toBe(`/api/v1/exchange-candidates?from=${date}&to=2026-10-26`);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });
  it("sends immutable create body, expected reservation versions and supplied idempotency key through CSRF", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "synthetic-csrf" }, meta }) : Response.json({ data: request, meta })); vi.stubGlobal("fetch", fetchMock);
    expect(await submitExchangeIntent(createIntent, "synthetic-create-key")).toEqual(request);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/v1/exchange-requests");
    expect(fetchMock.mock.calls[1][1].method).toBe("POST");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("synthetic-create-key");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(createIntent.input);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
  it("uses PATCH for owner allocation and POST for explicit reject/cancel with request versions", async () => {
    for (const intent of [{ kind: "ALLOCATE", id: request.id, input: { expectedVersion: 0, reservationId: source.id, instrumentCodesOrder: ["DRUMS", "KEYBOARD"] } }, { kind: "REJECT", id: request.id, input: { expectedVersion: 0 } }, { kind: "CANCEL", id: request.id, input: { expectedVersion: 0 } }] as ExchangeIntent[]) {
      const response = { ...request, status: intent.kind === "REJECT" ? "REJECTED" : intent.kind === "CANCEL" ? "CANCELED" : "PENDING" };
      const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "synthetic-csrf" }, meta }) : Response.json({ data: response, meta })); vi.stubGlobal("fetch", fetchMock);
      await submitExchangeIntent(intent, "synthetic-action-key");
      expect(fetchMock.mock.calls[1][1].method).toBe(intent.kind === "ALLOCATE" ? "PATCH" : "POST");
      expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(intent.input);
    }
  });
  it("rejects unrelated, partial and malformed successful bodies as uncertain, including incomplete atomic approval", () => {
    const completed = { ...request, status: "COMPLETED" as const, replacedReservationIds: [source.id, counterpart.id], reservations: [{ ...source, id: "new-source", originKind: "EXCHANGE" as const }, { ...counterpart, id: "new-target", originKind: "EXCHANGE" as const }] };
    expect(checkedExchange(completed, { kind: "APPROVE", id: request.id, input: { expectedVersion: 0 } })).toEqual(completed);
    for (const invalid of [null, { ...request, id: "" }, { ...request, offered: [] }, { ...request, preview: [{}] }, { ...request, requested: [{ ...counterpart, id: "unrelated" }] }, { ...completed, reservations: [completed.reservations[0]] }, { ...completed, replacedReservationIds: [source.id] }]) expect(() => checkedExchange(invalid as ExchangeResponse, createIntent)).toThrowError(expect.objectContaining({ code: "INVALID_RESPONSE" }));
    expect(() => checkedExchange(request, { kind: "APPROVE", id: request.id, input: { expectedVersion: 0 } })).toThrowError(expect.objectContaining({ code: "INVALID_RESPONSE" }));
  });
  it("retains the exact key/body on transport loss and forbids a new request until definitive resolution", () => {
    const state = new IdempotentRequestState<ExchangeIntent>(() => "immutable-exchange-key"); const mutable = structuredClone(createIntent); const prepared = state.prepare(mutable)!;
    if (mutable.kind === "CREATE") mutable.input.offered[0].expectedVersion = 99;
    expect(state.failed(new TypeError("lost response"))).toBe(true);
    expect(state.prepare(mutable)).toBeUndefined();
    expect(state.request).toEqual({ key: prepared.key, intent: createIntent });
    expect(state.failed(new ApiError(0, "OFFLINE", ""))).toBe(true);
    expect(state.request?.key).toBe("immutable-exchange-key");
    expect(state.failed(new ApiError(409, "EXCHANGE_EXPIRED", ""))).toBe(false);
    expect(state.request).toBeNull();
  });
  it("sends no exchange mutation or queue while offline and explains known failures without claiming lost responses rolled back", async () => {
    const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); vi.stubGlobal("navigator", { onLine: false });
    const failure = await submitExchangeIntent(createIntent, "offline-key").catch((error: unknown) => error);
    expect(failure).toMatchObject({ code: "OFFLINE" }); expect(fetchMock).not.toHaveBeenCalled();
    const state = new IdempotentRequestState<ExchangeIntent>(); state.prepare(createIntent); expect(state.failed(failure)).toBe(false);
    for (const code of ["RESERVATION_CONFLICT", "DAILY_LIMIT_EXCEEDED", "ASSET_FAULTY"]) expect(exchangeErrorMessage(new ApiError(409, code, "private echo"))).toContain("양측 기존 예약·점유·사용 한도는 유지");
    expect(exchangeErrorMessage(new ApiError(503, "RESERVATION_TEMPORARILY_UNAVAILABLE", "private echo"))).not.toContain("적용되지 않았");
    expect(exchangeErrorMessage(new ApiError(409, "EXCHANGE_INVALIDATED", ""))).toContain("무효화");
  });
});

describe("related queries and account/authority boundaries", () => {
  it("refreshes timetable, all personal/team ranges, old/new detail, exchange/candidate/list and current membership", async () => {
    for (const refresh of [refreshExchanges, refreshReservations, refreshDirectory]) {
      const client = new QueryClient(); const scope = directoryScope(member);
      const keys = [scheduleQuery(scope).queryKey, myReservationsQuery(scope, date, date).queryKey, myReservationsQuery(scope, date, "2026-10-26").queryKey, reservationQuery(scope, source.id).queryKey, reservationQuery(scope, "successor").queryKey, exchangesQuery(scope).queryKey, exchangeQuery(scope, request.id).queryKey, exchangeCandidatesQuery(scope, date, "2026-10-26").queryKey, memberQueryKey];
      keys.forEach((key) => client.setQueryData(key, {})); await refresh(client);
      keys.forEach((key) => expect(client.getQueryState(key)?.isInvalidated).toBe(true)); client.clear();
    }
  });
  it("removes old account or old role/team exchange queries and clears all state on logout", async () => {
    const client = new QueryClient(); const oldScope = directoryScope(member); const key = exchangeQuery(oldScope, request.id).queryKey; client.setQueryData(key, request);
    const changed = { ...member, teamMemberships: [] }; await pruneDirectoryCache(client, changed); expect(client.getQueryData(key)).toBeUndefined();
    client.setQueryData(exchangeQuery(directoryScope(changed), request.id).queryKey, request); await replaceSessionCache(client, null);
    expect(client.getQueriesData({ queryKey: ["exchanges"] })).toHaveLength(0); client.clear();
  });
});

describe("diary exchange forms expose accurate actions and asset ownership", () => {
  const render = (value: ExchangeResponse, account: Member = member, clock = now, disabled = false) => renderToStaticMarkup(createElement(ExchangeDetail, { request: value, member: account, now: clock, disabled, onAction: vi.fn() }));
  it("shows server-authorized cancel for a sender and no approve/reject for related readonly teammates", () => {
    expect(render(request)).toContain("요청 취소 내용 확인"); expect(render(request)).not.toContain("교환 승인 내용 확인");
    const readonly = render({ ...request, canCancel: false }); expect(readonly).not.toContain("내용 확인</button>"); expect(readonly).toContain("승인·거절·취소 권한이 없어요");
    expect(render({ ...request, canApprove: true, canReject: true, canCancel: false })).toContain("교환 승인 내용 확인");
  });
  it("hides mutation actions after the exact expiry boundary or any terminal state", () => {
    expect(pendingExchange(request, Date.parse(request.expiresAt))).toBe(false);
    for (const status of ["EXPIRED", "INVALIDATED", "REJECTED", "CANCELED", "COMPLETED"] as const) expect(render({ ...request, status })).not.toContain("내용 확인</button>");
    expect(render(request, member, Date.parse(request.expiresAt))).not.toContain("요청 취소 내용 확인");
    expect(render(request, member, Date.parse(request.expiresAt))).toContain("예약 시작 시각에 도달했어요");
  });
  it("allows allocation only to the personal owner and blocks approval until saved", () => {
    const dual = { ...source, instrumentCodes: ["DRUMS", "KEYBOARD"] as InstrumentCode[] };
    const value = { ...request, offered: [dual], allocationReservationIds: [source.id], canAllocateReservationIds: [source.id], canApprove: true, preview: request.preview.map((item) => item.side === "REQUESTER" ? { ...item, allocationRequired: true } : item) };
    expect(render(value)).toContain('id="allocation-9007199254740994"'); expect(render(value)).toContain("악기 순서 저장"); expect(render(value)).not.toContain("교환 승인 내용 확인");
    const admin = { ...member, id: "global-admin", globalRole: "GLOBAL_ADMIN" as const }; expect(render(value, admin)).not.toContain('id="allocation-'); expect(render(value, admin)).toContain("대신 순서를 선택할 수 없어요");
  });
  it("reopens a saved reversed asset allocation in the stored chronological order instead of source order", () => {
    const dual = { ...source, ...ninety, ownerUserId: member.id, creatorUserId: member.id, instrumentCodes: ["DRUMS", "KEYBOARD"] as InstrumentCode[] };
    const value = { ...request, version: 1, offered: [dual], requested: [{ ...source, ownerUserId: "502", creatorUserId: "502" }, { ...source, ...second, ownerUserId: "502", creatorUserId: "502" }], allocationReservationIds: [dual.id], canAllocateReservationIds: [dual.id], preview: [
      { ...request.preview[0], blockCode: "B02" as const, instrumentCodes: ["DRUMS"] as InstrumentCode[] },
      { ...request.preview[0], blockCode: "B01" as const, instrumentCodes: ["KEYBOARD"] as InstrumentCode[] }, request.preview[1],
    ] };
    const markup = render(value);
    const allocation = markup.match(/<select id="allocation-[^"]+"[^>]*>[\s\S]*?<\/select>/)?.[0] ?? "";
    expect(allocation).toContain('<option value="KEYBOARD" selected="">'); expect(allocation).not.toContain('<option value="DRUMS" selected="">');
    expect(markup).toContain("13:00~14:00"); expect(markup).toContain("드럼");
    expect(render({ ...request, status: "EXPIRED", stateReason: "EARLIEST_START_REACHED" })).toContain("가장 이른 예약의 시작 시각");
    expect(render({ ...request, status: "INVALIDATED", stateReason: "SUBJECT_INACTIVE" })).toContain("예약 주체가 비활성 상태");
  });
  it("marks all actions disabled while request outcome/refresh is pending and keeps source-preservation warnings", () => {
    const markup = render({ ...request, canApprove: true, canReject: true }, member, now, true);
    for (const button of markup.match(/<button[^>]*>[\s\S]*?<\/button>/g) ?? []) expect(button).toContain('disabled=""');
    expect(markup).toContain("양쪽 예약·점유·한도를 모두 유지"); expect(markup).toContain("다음 주 반복 배정은 유지"); expect(markup).toContain("주체·최초 예약자를 유지");
  });
  it("requires a before/after review before sending and distinguishes time changes and fixed exemption loss", () => {
    const markup = renderToStaticMarkup(createElement(CreateExchangeForm, { candidates: [candidate, second, ninety], now, disabled: false, initialId: candidate.id, onCreate: vi.fn() }));
    expect(markup).toContain("교환 내용 확인"); expect(markup).not.toContain("교환 요청 보내기</button>");
    expect(markup).toContain("요청만으로 새 시간을 확보하지 않으며"); expect(markup).toContain("최대 두 건 선택"); expect(markup).not.toContain("ownerUserId");
  });
  it("compares precise arrival blocks and lengths with preserved dual assets and changed 60-minute quota", () => {
    const merged = renderToStaticMarkup(createElement(ExchangeArrival, { title: "내 쪽 도착 시간", source: [candidate, second], target: [ninety] }));
    expect(merged).toContain("총 90분"); expect(merged).toContain("18:00~19:30"); expect(merged).toContain("드럼 · 건반"); expect(merged).toContain("한도 소비: 0분");
    const split = renderToStaticMarkup(createElement(ExchangeArrival, { title: "내 쪽 도착 시간", source: [{ ...ninety, instrumentCodes: ["DRUMS", "KEYBOARD"] }], target: [candidate, second] }));
    expect(split).toContain("총 120분"); expect(split).toContain("12:00~13:00"); expect(split).toContain("13:00~14:00"); expect(split).toContain("악기 하나씩 순서 선택"); expect(split).toContain("한도 소비: 120분");
  });
});
