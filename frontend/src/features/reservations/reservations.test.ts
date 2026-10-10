import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import ReservationsPage from "@/app/reservations/page";
import { ApiError } from "@/lib/api/client";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { directoryScope, pruneDirectoryCache } from "@/features/directory/queries";
import { cancelReservation, createReservation } from "./api";
import { bookingInput } from "./booking";
import { reservationErrorMessage } from "./errors";
import { ReservationRequestState } from "./request-state";
import { addDate, blockLabel, phaseAt, remainingMinutes, seoulDate, serverNow } from "./time";
import type { ReservationDetail, ReservationIntent, ScheduleBlock, ScheduleDay, ScheduleSnapshot } from "./types";
import { DesktopSchedule, MobileSchedule, ReservationCard, cancellationNotice, quotaHistoryLabel } from "./view";

const meta = { serverTime: "2026-10-12T14:10:00+09:00", timeZone: "Asia/Seoul" };
const member: Member = { id: "9007199254740993", loginId: "fixture.reservation", displayName: "가상 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [{ teamId: "17", teamName: "가상 정규조", kind: "REGULAR", role: "MEMBER" }] };
const block: ScheduleBlock = { blockCode: "B03", startsAt: "2026-10-12T14:00:00+09:00", endsAt: "2026-10-12T15:00:00+09:00", durationMinutes: 60, phase: "IN_PROGRESS", remainingMinutes: 50, canReservePersonal: true, canReserveTeam: true, availableInstrumentCodes: ["DRUMS", "KEYBOARD"], faultyInstrumentCodes: [], teamWarnings: [], reservations: [] };
const day: ScheduleDay = { date: "2026-10-12", openingStatus: "OPEN", personalQuota: { usedMinutes: 60, remainingMinutes: 60 }, teamQuotas: [{ teamId: "17", teamName: "가상 정규조", usedMinutes: 120, remainingMinutes: 0 }], blocks: [block] };
const snapshot: ScheduleSnapshot = { today: day.date, bookableThrough: "2026-10-26", serverTime: meta.serverTime, timeZone: "Asia/Seoul", instruments: [{ code: "DRUMS", name: "드럼", status: "NORMAL", version: 0 }, { code: "KEYBOARD", name: "건반", status: "NORMAL", version: 0 }], days: [day], receivedAtMillis: 5_000 };
const reservation: ReservationDetail = { id: "9007199254740994", version: 7, subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, instrumentCodes: ["DRUMS"], status: "CONFIRMED", phase: "IN_PROGRESS", isMine: true, canCancel: true, ownerUserId: member.id, creatorUserId: member.id, date: day.date, blockCode: "B03", startsAt: block.startsAt, endsAt: block.endsAt, usableFrom: meta.serverTime, quotaMinutes: 60, quotaExempt: false, originKind: "MANUAL", teamFaultWarnings: [], canceledAt: null, cancelReason: null };
const now = Date.parse(meta.serverTime);
afterEach(() => vi.unstubAllGlobals());

describe("server time and fixed blocks", () => {
  it("corrects device clock offset and respects Seoul midnight and B10's next-day end", () => {
    expect(serverNow(snapshot, 35_000)).toBe(now + 30_000);
    expect(serverNow(snapshot, 0)).toBe(now);
    expect(seoulDate(Date.parse("2026-10-11T15:00:00Z"))).toBe("2026-10-12");
    expect(seoulDate(Date.parse("2026-10-11T14:59:59Z"))).toBe("2026-10-11");
    expect(blockLabel({ startsAt: "2026-10-12T22:30:00+09:00", endsAt: "2026-10-13T00:00:00+09:00" })).toBe("22:30~24:00");
    expect(phaseAt(block.startsAt, block.endsAt, Date.parse(block.endsAt))).toBe("ENDED");
    expect(remainingMinutes(block, now + 1)).toBe(50);
    expect(remainingMinutes(block, Date.parse(block.endsAt))).toBe(0);
  });
});

describe("booking input follows the current server snapshot", () => {
  it("creates one personal instrument and preserves canonical block/date without free-form time or user IDs", () => {
    expect(bookingInput(member, day, block, "PERSONAL", "DRUMS", now)).toEqual({ subjectType: "PERSONAL", date: day.date, blockCode: "B03", instrumentCodes: ["DRUMS"] });
    expect(typeof bookingInput(member, day, block, "PERSONAL", "VOCAL", now)).toBe("string");
    expect(typeof bookingInput(member, day, { ...block, canReservePersonal: false }, "PERSONAL", "DRUMS", now)).toBe("string");
    expect(typeof bookingInput(member, { ...day, openingStatus: "PREPARING" }, block, "PERSONAL", "DRUMS", now)).toBe("string");
    expect(typeof bookingInput(member, day, block, "PERSONAL", "DRUMS", Date.parse(block.endsAt))).toBe("string");
  });
  it("does not mix personal and team limits or let global admins create for an unsubscribed team", () => {
    expect(typeof bookingInput(member, day, block, "17", "DRUMS", now)).toBe("string");
    expect(bookingInput(member, day, { ...block, blockCode: "B07", durationMinutes: 90 }, "17", "DRUMS", now)).toEqual({ subjectType: "TEAM", date: day.date, blockCode: "B07", teamId: "17" });
    expect(typeof bookingInput({ ...member, globalRole: "GLOBAL_ADMIN" }, day, block, "unsubscribed-team", "", now)).toBe("string");
    expect(typeof bookingInput(member, { ...day, personalQuota: { usedMinutes: 120, remainingMinutes: 0 } }, block, "PERSONAL", "DRUMS", now)).toBe("string");
  });
});

describe("explicit retry uses the exact idempotent request", () => {
  it("retains a snapshot and key after uncertain transport failure, rejects a new intent, then releases after a known rejection", () => {
    let counter = 0; const requests = new ReservationRequestState(() => `fixture-request-key-${++counter}`);
    const intent: ReservationIntent = { kind: "CREATE", input: { subjectType: "PERSONAL", date: day.date, blockCode: "B03", instrumentCodes: ["DRUMS"] } };
    const first = requests.prepare(intent)!;
    if (intent.input.subjectType === "PERSONAL") intent.input.instrumentCodes[0] = "KEYBOARD";
    expect(requests.failed(new TypeError("transport disconnected"))).toBe(true);
    expect(requests.prepare(intent)).toBeUndefined();
    expect(requests.request?.key).toBe(first.key);
    expect(requests.request?.intent).toMatchObject({ input: { instrumentCodes: ["DRUMS"] } });
    expect(requests.failed(new ApiError(0, "OFFLINE", ""))).toBe(true);
    expect(requests.request?.key).toBe(first.key);
    for (const [status, code] of [[403, "CSRF_INVALID"], [200, "INVALID_CSRF_RESPONSE"], [429, "TOO_MANY_REQUESTS"]] as const) {
      expect(requests.failed(new ApiError(status, code, ""))).toBe(true);
      expect(requests.request?.key).toBe(first.key);
      expect(requests.request?.intent).toMatchObject({ input: { instrumentCodes: ["DRUMS"] } });
    }
    expect(requests.failed(new ApiError(409, "RESERVATION_CONFLICT", ""))).toBe(false);
    expect(requests.prepare(intent)?.key).toBe("fixture-request-key-2");
    requests.clear(); expect(requests.request).toBeNull();
  });
  it("releases a request rejected before its first write rather than blocking a corrected request", () => {
    const requests = new ReservationRequestState(() => "fixture-first-key");
    const intent: ReservationIntent = { kind: "CANCEL", reservationId: reservation.id, input: { expectedVersion: reservation.version } };
    for (const [status, code] of [[0, "OFFLINE"], [403, "CSRF_INVALID"], [200, "INVALID_CSRF_RESPONSE"], [429, "TOO_MANY_REQUESTS"]] as const) {
      requests.prepare(intent); expect(requests.failed(new ApiError(status, code, ""))).toBe(false);
      expect(requests.request).toBeNull();
    }
  });
  it("sends the supplied key, current cancel version and string IDs through the shared CSRF client without automatic replay", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? Response.json({ data: { headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }, meta }) : Response.json({ data: reservation, meta }));
    vi.stubGlobal("fetch", fetchMock);
    await createReservation({ subjectType: "PERSONAL", date: day.date, blockCode: "B03", instrumentCodes: ["DRUMS"] }, "fixture-idempotency-key");
    await cancelReservation(reservation.id, { expectedVersion: 7 }, "fixture-cancel-key");
    expect(new Headers(fetchMock.mock.calls[1][1].headers).get("Idempotency-Key")).toBe("fixture-idempotency-key");
    expect(new Headers(fetchMock.mock.calls[3][1].headers).get("Idempotency-Key")).toBe("fixture-cancel-key");
    expect(fetchMock.mock.calls[3][0]).toBe("/api/v1/reservations/9007199254740994/cancel");
    expect(JSON.parse(fetchMock.mock.calls[3][1].body)).toEqual({ expectedVersion: 7 });
    expect(fetchMock).toHaveBeenCalledTimes(4);
  });
  it("never renders untrusted server echoes and explains conflict/limit errors", () => {
    expect(reservationErrorMessage(new ApiError(409, "DAILY_LIMIT_EXCEEDED", "private echoed data"))).toContain("하루 60분");
    expect(reservationErrorMessage(new ApiError(409, "PERSONAL_OVERLAP", "private echoed data"))).toContain("같은 시간");
    expect(reservationErrorMessage(new ApiError(503, "RESERVATION_TEMPORARILY_UNAVAILABLE", "private echoed data"))).toContain("같은 요청 결과 다시 확인");
    expect(reservationErrorMessage(new ApiError(500, "UNKNOWN", "private echoed data"))).not.toContain("echoed");
  });
});

describe("reservation views and cache permissions", () => {
  it("shows residual time and asset states without exposing another personal user's name", () => {
    const occupied = { ...block, reservations: [{ ...reservation, isMine: false, canCancel: false, privateName: "다른 개인의 비공개 이름" }] };
    const markup = renderToStaticMarkup(createElement(MobileSchedule, { day: { ...day, blocks: [occupied] }, instruments: snapshot.instruments, now, onSelect: vi.fn() }));
    expect(markup).toContain("잔여 약 50분"); expect(markup).toContain("악기별 공유"); expect(markup).toContain("예약됨"); expect(markup).not.toContain("다른 개인의 비공개 이름");
  });
  it("only renders cancellation for server-authorized active reservations before their end", () => {
    const render = (detail: ReservationDetail, clock = now) => renderToStaticMarkup(createElement(ReservationCard, { reservation: detail, now: clock, disabled: false, onCancel: vi.fn() }));
    expect(render(reservation)).toContain("취소 확인</button>");
    expect(render({ ...reservation, canCancel: false })).not.toContain("취소 확인</button>");
    expect(render({ ...reservation, status: "CANCELED" })).not.toContain("취소 확인</button>");
    expect(render(reservation, Date.parse(block.endsAt))).not.toContain("취소 확인</button>");
    expect(quotaHistoryLabel({ ...reservation, status: "CANCELED", canceledAt: "2026-10-12T13:59:59+09:00" })).toContain("한도 복원");
    expect(quotaHistoryLabel({ ...reservation, status: "CANCELED", canceledAt: block.startsAt })).toContain("60분 유지");
  });
  it("does not claim quota restoration or consumption for a canceled 90-minute block", () => {
    const ninety = { ...reservation, quotaMinutes: 0, startsAt: "2026-10-12T18:00:00+09:00", endsAt: "2026-10-12T19:30:00+09:00" };
    for (const canceledAt of ["2026-10-12T17:00:00+09:00", "2026-10-12T18:10:00+09:00"]) {
      const label = quotaHistoryLabel({ ...ninety, status: "CANCELED", canceledAt });
      expect(label).toContain("60분 한도 합산 없음"); expect(label).not.toContain("복원"); expect(label).not.toContain("유지");
    }
    expect(quotaHistoryLabel(ninety)).toContain("60분 한도 합산 없음");
  });
  it("confirms cancellation using the actual quota and phase of the selected reservation", () => {
    expect(cancellationNotice(reservation, now)).toContain("당일 사용 한도는 유지");
    expect(cancellationNotice(reservation, Date.parse(block.startsAt) - 1)).toContain("한도를 복원");
    expect(cancellationNotice({ ...reservation, quotaMinutes: 0 }, now)).toContain("한도에 합산하지 않아요");
    expect(cancellationNotice({ ...reservation, quotaMinutes: 0 }, Date.parse(block.startsAt) - 1)).not.toContain("복원");
    expect(cancellationNotice({ ...reservation, quotaExempt: true }, now)).toContain("한도 면제");
    expect(cancellationNotice(reservation, Date.parse(block.endsAt))).toContain("취소할 수 없어요");
  });
  it("aligns all week rows with the actual remaining dates at the 15-day horizon", () => {
    const lastDate = addDate(snapshot.today, 14);
    for (const count of [1, 6, 7]) {
      const days = Array.from({ length: count }, (_, index) => ({ ...day, date: addDate(lastDate, index - count + 1) }));
      const markup = renderToStaticMarkup(createElement(DesktopSchedule, { days, now, selectedDate: lastDate, onSelect: vi.fn() }));
      expect(markup).toContain(`grid-template-columns:5rem repeat(${count}, minmax(0, 1fr))`);
      expect(markup.match(/<button /g)).toHaveLength(count * day.blocks.length);
      expect(markup).toContain("10월 26일");
    }
  });
  it("hides schedule UI from anonymous accounts and removes previous-authority reservation caches", async () => {
    const client = new QueryClient(); client.setQueryData(memberQueryKey, null);
    const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(ReservationsPage))));
    expect(markup).toContain("로그인이 필요해요"); expect(markup).not.toContain('id="reservation-date"');
    client.setQueryData(["reservations", directoryScope({ ...member, globalRole: "GLOBAL_ADMIN" }), "schedule"], "old admin data");
    client.setQueryData(["reservations", directoryScope(member), "schedule"], "current scope");
    await pruneDirectoryCache(client, member);
    expect(client.getQueryData(["reservations", directoryScope({ ...member, globalRole: "GLOBAL_ADMIN" }), "schedule"])).toBeUndefined();
    expect(client.getQueryData(["reservations", directoryScope(member), "schedule"])).toBe("current scope");
    client.clear();
  });
});
