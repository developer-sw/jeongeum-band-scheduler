import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api/client";
import type { Member } from "@/features/auth/types";
import { memberQueryKey } from "@/features/auth/session-cache";
import { directoryScope } from "@/features/directory/queries";
import { changeReservation, changedReservation, submitReservationIntent } from "./api";
import { ChangeForm } from "./change-form";
import { canChangeReservation, changeInput, changeQuota, changeUnavailableReason, isUnchangedReservation } from "./change";
import { SuccessorLinks } from "./detail-panel";
import { reservationActionErrorMessage } from "./errors";
import { myReservationsQuery, refreshReservations, reservationQuery, scheduleQuery } from "./queries";
import { ReservationRequestState } from "./request-state";
import type { ChangeReservationResult, InstrumentCode, ReservationDetail, ReservationIntent, ScheduleBlock, ScheduleDay, ScheduleSnapshot } from "./types";
import { ReservationCard } from "./view";

const date = "2026-10-12";
const meta = { serverTime: `${date}T11:00:00+09:00`, timeZone: "Asia/Seoul" };
const now = Date.parse(meta.serverTime);
const member: Member = { id: "501", loginId: "synthetic.change", displayName: "가상 변경 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [{ teamId: "71", teamName: "가상 변경 정규조", kind: "REGULAR", role: "MEMBER" }] };
const codes: InstrumentCode[] = ["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"];
const block: ScheduleBlock = { blockCode: "B01", startsAt: `${date}T12:00:00+09:00`, endsAt: `${date}T13:00:00+09:00`, durationMinutes: 60, phase: "FUTURE", remainingMinutes: 60, canReservePersonal: true, canReserveTeam: true, availableInstrumentCodes: codes, faultyInstrumentCodes: [], teamWarnings: [], reservations: [] };
const next: ScheduleBlock = { ...block, blockCode: "B02", startsAt: `${date}T13:00:00+09:00`, endsAt: `${date}T14:00:00+09:00` };
const ninety: ScheduleBlock = { ...block, blockCode: "B07", startsAt: `${date}T18:00:00+09:00`, endsAt: `${date}T19:30:00+09:00`, durationMinutes: 90, remainingMinutes: 90 };
const day: ScheduleDay = { date, openingStatus: "OPEN", personalQuota: { usedMinutes: 120, remainingMinutes: 0 }, teamQuotas: [{ teamId: "71", teamName: "가상 변경 정규조", usedMinutes: 60, remainingMinutes: 60 }], blocks: [block, next, ninety] };
const schedule: ScheduleSnapshot = { today: date, bookableThrough: "2026-10-26", serverTime: meta.serverTime, timeZone: "Asia/Seoul", receivedAtMillis: 0, instruments: codes.map((code) => ({ code, name: code, status: "NORMAL", version: 0 })), days: [day] };
const source: ReservationDetail = { id: "9007199254740994", version: 7, subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, instrumentCodes: ["DRUMS"], status: "CONFIRMED", phase: "FUTURE", originKind: "MANUAL", quotaExempt: false, isMine: true, canCancel: true, canChange: true, ownerUserId: member.id, creatorUserId: member.id, date, blockCode: "B01", startsAt: block.startsAt, endsAt: block.endsAt, usableFrom: block.startsAt, quotaMinutes: 60, teamFaultWarnings: [], canceledAt: null, cancelReason: null, successorIds: [], dualAssetException: false };
const successor: ReservationDetail = { ...source, id: "9007199254740995", blockCode: "B02", startsAt: next.startsAt, endsAt: next.endsAt, usableFrom: next.startsAt, originKind: "CHANGE", version: 0 };
const intent: ReservationIntent = { kind: "CHANGE", reservationId: source.id, input: { expectedVersion: source.version, date, blockCode: "B02", instrumentCodes: ["KEYBOARD"] } };
const team: ReservationDetail = { ...source, subjectType: "TEAM", purpose: "REGULAR", teamId: "71", teamName: "가상 변경 정규조", ownerUserId: null, instrumentCodes: codes };

afterEach(() => vi.unstubAllGlobals());

describe("change permissions and destination validation", () => {
  it("uses source status, exact start boundary and server authorization with a concrete unavailable reason", () => {
    expect(canChangeReservation(source, now)).toBe(true);
    expect(changeUnavailableReason(source, Date.parse(source.startsAt))).toContain("이미 시작한 예약");
    expect(changeUnavailableReason(source, Date.parse(source.endsAt))).toContain("종료된 예약");
    expect(changeUnavailableReason({ ...source, status: "CANCELED" }, now)).toContain("취소된 예약");
    expect(changeUnavailableReason({ ...source, status: "REPLACED" }, now)).toContain("후속 예약");
    expect(changeUnavailableReason({ ...source, canChange: false }, now)).toContain("서버가 현재 예약의 변경 권한");
    expect(typeof changeInput(member, { ...source, canChange: false }, schedule, date, "B02", ["KEYBOARD"], now)).toBe("string");
  });

  it("rechecks current team membership and creator/leader authority without changing subject or creator", () => {
    expect(changeInput(member, team, schedule, date, "B02", [], now)).toEqual({ expectedVersion: 7, date, blockCode: "B02" });
    const othersTeamBooking = { ...team, creatorUserId: "other-member" };
    expect(changeInput(member, othersTeamBooking, schedule, date, "B02", [], now)).toContain("다른 팀원이 만든 예약");
    expect(typeof changeInput({ ...member, teamMemberships: [] }, team, schedule, date, "B02", [], now)).toBe("string");
    expect(changeInput({ ...member, teamMemberships: [{ ...member.teamMemberships[0], role: "LEADER" }] }, othersTeamBooking, schedule, date, "B02", [], now)).toEqual({ expectedVersion: 7, date, blockCode: "B02" });
  });

  it("subtracts the source's same-day quota while rejecting a genuinely exhausted destination date", () => {
    expect(changeQuota(member, source, day)).toEqual({ usedMinutes: 60, remainingMinutes: 60 });
    expect(changeInput(member, source, schedule, date, "B02", ["KEYBOARD"], now)).toEqual(intent.input);
    const tomorrow = "2026-10-13";
    const tomorrowDay = { ...day, date: tomorrow, blocks: [{ ...next, startsAt: `${tomorrow}T13:00:00+09:00`, endsAt: `${tomorrow}T14:00:00+09:00` }] };
    expect(changeInput(member, source, { ...schedule, days: [...schedule.days, tomorrowDay] }, tomorrow, "B02", ["KEYBOARD"], now)).toContain("한도를 초과");
  });

  it("does not carry the original fixed exemption to a relocated 60-minute reservation", () => {
    const fixed = { ...team, originKind: "FIXED" as const, quotaExempt: true, quotaMinutes: 0 };
    const exhausted = { ...schedule, days: [{ ...day, teamQuotas: [{ ...day.teamQuotas[0], usedMinutes: 120, remainingMinutes: 0 }] }] };
    expect(changeInput(member, fixed, exhausted, date, "B02", [], now)).toContain("한도를 초과");
    expect(changeInput(member, fixed, exhausted, date, "B01", [], now)).toEqual({ expectedVersion: 7, date, blockCode: "B01" });
  });

  it("excludes source occupancy and avoids actor quota/overlap when an admin changes another owner's reservation", () => {
    const occupiedSource = { ...schedule, days: [{ ...day, blocks: [{ ...block, canReservePersonal: false, canReserveTeam: false, availableInstrumentCodes: [], reservations: [source] }, next] }] };
    expect(changeInput(member, source, occupiedSource, date, "B01", ["KEYBOARD"], now)).toMatchObject({ instrumentCodes: ["KEYBOARD"] });
    const admin = { ...member, globalRole: "GLOBAL_ADMIN" as const, teamMemberships: [] };
    const other = { ...source, ownerUserId: "another-owner", creatorUserId: "another-owner", isMine: false, quotaMinutes: 0 };
    const actorAtDestination = { ...source, id: "actor-reservation", instrumentCodes: ["BASS" as const] };
    const shared = { ...schedule, days: [{ ...day, blocks: [{ ...next, canReservePersonal: false, reservations: [actorAtDestination] }] }] };
    expect(changeQuota(admin, other, day)).toBeUndefined();
    expect(changeQuota(admin, team, day)).toBeUndefined();
    expect(changeInput(admin, other, shared, date, "B02", ["KEYBOARD"], now)).toMatchObject({ instrumentCodes: ["KEYBOARD"] });
    expect(changeInput(member, source, shared, date, "B02", ["KEYBOARD"], now)).toContain("본인의 다른 개인 예약");
  });

  it("checks open dates and rejects ended destinations while accepting remaining-time destinations", () => {
    const laterSource = { ...source, blockCode: "B07" as const, startsAt: ninety.startsAt, endsAt: ninety.endsAt, usableFrom: ninety.startsAt, quotaMinutes: 0 };
    const available = { ...schedule, days: [{ ...day, personalQuota: { usedMinutes: 60, remainingMinutes: 60 } }] };
    expect(changeInput(member, laterSource, available, date, "B01", ["KEYBOARD"], Date.parse(`${date}T12:10:00+09:00`))).toMatchObject({ blockCode: "B01" });
    expect(changeInput(member, laterSource, available, date, "B01", ["KEYBOARD"], Date.parse(block.endsAt))).toContain("종료되지 않은");
    expect(changeInput(member, source, { ...schedule, days: [{ ...day, openingStatus: "PREPARING" }] }, date, "B02", ["KEYBOARD"], now)).toContain("개방된 예약 날짜");
    expect(typeof changeInput(member, source, schedule, "2026-10-27", "B02", ["KEYBOARD"], now)).toBe("string");
  });

  it("retains ordinary one-instrument shape and permits at most two for an existing dual 90-minute exception", () => {
    expect(changeInput(member, source, schedule, date, "B07", ["DRUMS", "KEYBOARD"], now)).toContain("한 종류");
    const dual = { ...source, blockCode: "B07" as const, startsAt: ninety.startsAt, endsAt: ninety.endsAt, usableFrom: ninety.startsAt, instrumentCodes: ["DRUMS", "KEYBOARD"] as InstrumentCode[], originKind: "EXCHANGE" as const, quotaMinutes: 0, dualAssetException: true };
    expect(changeInput(member, dual, schedule, date, "B07", ["KEYBOARD", "BASS"], now)).toMatchObject({ instrumentCodes: ["KEYBOARD", "BASS"] });
    expect(changeInput(member, { ...dual, instrumentCodes: ["DRUMS"] }, schedule, date, "B07", ["KEYBOARD", "BASS"], now)).toMatchObject({ instrumentCodes: ["KEYBOARD", "BASS"] });
    expect(changeInput(member, { ...dual, instrumentCodes: ["DRUMS"], dualAssetException: false }, schedule, date, "B07", ["KEYBOARD", "BASS"], now)).toContain("한 종류");
    expect(changeInput(member, dual, schedule, date, "B07", ["DRUMS", "KEYBOARD", "BASS"], now)).toContain("최대 두 개");
    expect(changeInput(member, dual, schedule, date, "B02", ["DRUMS", "KEYBOARD"], now)).toContain("한 종류");
    expect(changeInput(member, dual, { ...schedule, days: [{ ...day, personalQuota: { usedMinutes: 60, remainingMinutes: 60 } }] }, date, "B02", ["KEYBOARD"], now)).toMatchObject({ instrumentCodes: ["KEYBOARD"] });
  });

  it("recognizes instrument-set no-ops and keeps faulty assets only for an exact unchanged request", () => {
    const faulty = { ...schedule, instruments: schedule.instruments.map((item) => item.code === "DRUMS" ? { ...item, status: "FAULTY" as const } : item) };
    expect(changeInput(member, source, faulty, date, "B01", ["DRUMS"], now)).toMatchObject({ blockCode: "B01" });
    expect(changeInput(member, source, faulty, date, "B02", ["DRUMS"], now)).toContain("고장 악기");
    expect(isUnchangedReservation({ ...source, instrumentCodes: ["DRUMS", "KEYBOARD"] }, date, "B01", ["KEYBOARD", "DRUMS"])).toBe(true);
    expect(isUnchangedReservation(source, date, "B01", ["DRUMS", "DRUMS"])).toBe(false);
  });

  it("rejects third-party occupancy while allowing warned team practice", () => {
    const other = { ...source, id: "other", isMine: false, ownerUserId: "other-owner" };
    const occupied = { ...schedule, days: [{ ...day, blocks: [{ ...next, reservations: [other] }] }] };
    expect(changeInput(member, source, occupied, date, "B02", ["DRUMS"], now)).toContain("다른 예약이 사용");
    expect(changeInput(member, team, occupied, date, "B02", [], now)).toContain("다른 예약이 점유");
    const warned = { ...schedule, days: [{ ...day, blocks: [{ ...next, teamWarnings: ["DRUMS" as const], faultyInstrumentCodes: ["DRUMS" as const] }] }] };
    expect(changeInput(member, team, warned, date, "B02", [], now)).toMatchObject({ blockCode: "B02" });
  });
});

describe("change requests preserve exact identity and unknown outcomes", () => {
  it("sends PATCH, expectedVersion and the supplied key through the shared CSRF client", async () => {
    const response = { replacedReservationIds: [source.id], reservations: [successor] };
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "synthetic-csrf" }, meta }) : Response.json({ data: response, meta }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await changeReservation(source.id, intent.input, "synthetic-change-key")).toEqual(response);
    expect(fetchMock.mock.calls[1][0]).toBe(`/api/v1/reservations/${source.id}`);
    expect(fetchMock.mock.calls[1][1].method).toBe("PATCH");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("synthetic-change-key");
    expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual(intent.input);
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it("normalizes one current successor or no-op and rejects empty, unrelated and partial success responses", () => {
    expect(changedReservation({ replacedReservationIds: [source.id], reservations: [successor] }, source.id)).toEqual(successor);
    expect(changedReservation({ replacedReservationIds: [], reservations: [source] }, source.id)).toEqual(source);
    expect(changedReservation({ replacedReservationIds: [source.id], reservations: [{ ...successor, status: "CANCELED", canChange: false, canCancel: false }] }, source.id).status).toBe("CANCELED");
    for (const invalid of [null, { replacedReservationIds: [source.id], reservations: [] }, { replacedReservationIds: [], reservations: [successor] }, { replacedReservationIds: ["unrelated"], reservations: [successor] }, { replacedReservationIds: [source.id], reservations: [successor, source] }, { replacedReservationIds: [source.id], reservations: [{ id: successor.id }] }]) {
      expect(() => changedReservation(invalid as unknown as ChangeReservationResult, source.id)).toThrowError(expect.objectContaining({ code: "INVALID_RESPONSE" }));
    }
  });

  it("keeps a malformed post-write response uncertain and refuses a different body, reservation or operation", async () => {
    const requests = new ReservationRequestState(() => "synthetic-uncertain-change-key");
    const mutable: ReservationIntent = structuredClone(intent);
    const pending = requests.prepare(mutable)!;
    if (mutable.kind === "CHANGE") { mutable.input.expectedVersion = 99; mutable.input.date = "2026-10-13"; mutable.input.instrumentCodes![0] = "BASS"; }
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "synthetic-csrf" }, meta }) : Response.json({ data: { replacedReservationIds: [source.id], reservations: [] }, meta }));
    vi.stubGlobal("fetch", fetchMock);
    const failure = await submitReservationIntent(pending.intent, pending.key).catch((error: unknown) => error);
    expect(requests.failed(failure)).toBe(true);
    expect(requests.request).toEqual({ key: "synthetic-uncertain-change-key", intent });
    for (const replacement of [mutable, { ...intent, reservationId: "different-source" }, { kind: "CANCEL", reservationId: source.id, input: { expectedVersion: 7 } }] as ReservationIntent[]) expect(requests.prepare(replacement)).toBeUndefined();
    expect(requests.failed(new ApiError(0, "OFFLINE", ""))).toBe(true);
    expect(requests.request?.key).toBe(pending.key);
    expect(requests.failed(new ApiError(409, "DAILY_LIMIT_EXCEEDED", ""))).toBe(false);
    expect(requests.request).toBeNull();
  });

  it("does not send or queue a PATCH while offline", async () => {
    const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); vi.stubGlobal("navigator", { onLine: false });
    const requests = new ReservationRequestState(() => "synthetic-offline-key");
    const pending = requests.prepare(intent)!;
    const failure = await submitReservationIntent(pending.intent, pending.key).catch((error: unknown) => error);
    expect(failure).toMatchObject({ code: "OFFLINE" });
    expect(requests.failed(failure)).toBe(false);
    expect(fetchMock).not.toHaveBeenCalled();
    expect(requests.request).toBeNull();
  });

  it("explains preserved reservations after known conflict/limit rejection without claiming an uncertain write failed", () => {
    for (const code of ["RESERVATION_CONFLICT", "DAILY_LIMIT_EXCEEDED"]) expect(reservationActionErrorMessage(new ApiError(409, code, "private echo"), intent)).toContain("기존 예약·점유·사용 한도는 유지");
    expect(reservationActionErrorMessage(new ApiError(503, "RESERVATION_TEMPORARILY_UNAVAILABLE", "private echo"), intent)).not.toContain("적용되지 않았");
    expect(reservationActionErrorMessage(new ApiError(409, "VERSION_CONFLICT", "private echo"), intent)).toContain("최신 상세");
  });

  it("invalidates timetable, all mine ranges including my-schedule, source/successor details and member authority", async () => {
    const client = new QueryClient(); const scope = directoryScope(member);
    const keys = [scheduleQuery(scope).queryKey, myReservationsQuery(scope, date, date).queryKey, myReservationsQuery(scope, date, schedule.bookableThrough).queryKey, reservationQuery(scope, source.id).queryKey, reservationQuery(scope, successor.id).queryKey, memberQueryKey];
    for (const key of keys) client.setQueryData(key, {});
    await refreshReservations(client);
    for (const key of keys) expect(client.getQueryState(key)?.isInvalidated).toBe(true);
    client.clear();
  });
});

describe("change form and reservation history", () => {
  it("fixes subject/creator and requires review before an initial PATCH, with server validation for other-owner quotas", () => {
    const markup = renderToStaticMarkup(createElement(ChangeForm, { member, reservation: source, schedule, now, disabled: false, onChange: vi.fn() }));
    expect(markup).toContain("예약 주체와 최초 예약자는 그대로 유지");
    expect(markup).not.toContain('id="reservation-subject"');
    expect(markup).toContain("변경 내용 확인</button>");
    expect(markup).not.toContain("예약 변경 확정</button>");
    const otherOwner = renderToStaticMarkup(createElement(ChangeForm, { member: { ...member, globalRole: "GLOBAL_ADMIN", teamMemberships: [] }, reservation: { ...source, ownerUserId: "other-owner", creatorUserId: "other-owner", isMine: false }, schedule, now, disabled: false, onChange: vi.fn() }));
    expect(otherOwner).toContain("서버가 해당 예약 주체의 최신 한도");
    expect(otherOwner).not.toContain("본인의 선택 날짜 60분 사용량");
  });

  it("shows why started or unauthorized reservations cannot submit and keeps all mutation controls locked while busy", () => {
    const render = (reservation: ReservationDetail, clock: number, disabled = false) => renderToStaticMarkup(createElement(ChangeForm, { member, reservation, schedule, now: clock, disabled, onChange: vi.fn() }));
    expect(render(source, Date.parse(source.startsAt))).toContain("이미 시작한 예약은 변경할 수 없어요");
    expect(render({ ...source, canChange: false }, now)).toContain("서버가 현재 예약의 변경 권한");
    expect(render(source, now, true)).toMatch(/<button type="submit"[^>]*disabled=""/);
    const card = (reservation: ReservationDetail, clock: number) => renderToStaticMarkup(createElement(ReservationCard, { reservation, now: clock, disabled: false, onCancel: vi.fn(), onChange: vi.fn() }));
    expect(card(source, now)).toContain("예약 변경</button>");
    expect(card({ ...source, canChange: false }, now)).not.toContain("예약 변경</button>");
    expect(card(source, Date.parse(source.startsAt))).not.toContain("예약 변경</button>");
    expect(card({ ...source, status: "REPLACED" }, now)).not.toContain("예약 변경</button>");
  });

  it("links each successor of a replaced history row, including multiple results, and prevents leaving uncertain state", () => {
    const replaced = { ...source, status: "REPLACED" as const, canChange: false, canCancel: false, successorIds: [successor.id, "two/encoded"] };
    const markup = renderToStaticMarkup(createElement(SuccessorLinks, { reservation: replaced }));
    expect(markup).toContain(`/reservations?reservationId=${successor.id}`);
    expect(markup).toContain("/reservations?reservationId=two%2Fencoded");
    expect(markup).toContain("현재 상태를 확인");
    expect(renderToStaticMarkup(createElement(SuccessorLinks, { reservation: replaced, disabled: true }))).not.toContain('href="');
    expect(renderToStaticMarkup(createElement(SuccessorLinks, { reservation: source }))).toBe("");
  });
});
