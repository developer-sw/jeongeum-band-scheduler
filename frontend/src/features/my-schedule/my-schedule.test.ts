import { createElement, type ComponentType } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import MySchedulePage from "@/app/my-schedule/page";
import ReservationsPage from "@/app/reservations/page";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { directoryScope, pruneDirectoryCache } from "@/features/directory/queries";
import { getMyReservations } from "@/features/reservations/api";
import { myReservationsQuery, refreshReservations, scheduleQuery } from "@/features/reservations/queries";
import { addDate } from "@/features/reservations/time";
import type { ReservationDetail, ScheduleSnapshot } from "@/features/reservations/types";
import { myTeamSchedule } from "./schedule";
import { MyTeamScheduleList } from "./view";

const navigation = vi.hoisted(() => ({ date: "" }));
vi.mock("next/navigation", () => ({ usePathname: () => "/my-schedule", useSearchParams: () => new URLSearchParams(navigation.date ? { date: navigation.date } : {}) }));

const member: Member = { id: "9007199254740993", loginId: "fixture.schedule", displayName: "가상 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [
  { teamId: "17", teamName: "가상 정규조 하나", kind: "REGULAR", role: "MEMBER" },
  { teamId: "18", teamName: "가상 정규조 둘", kind: "REGULAR", role: "LEADER" },
  { teamId: "19", teamName: "가상 추가조", kind: "ADDITIONAL", role: "MEMBER" },
] };
const today = "2026-10-12";
const snapshot: ScheduleSnapshot = { today, bookableThrough: "2026-10-26", serverTime: "2026-10-12T14:10:00+09:00", timeZone: "Asia/Seoul", receivedAtMillis: 0, instruments: [],
  days: Array.from({ length: 15 }, (_, index) => ({ date: addDate(today, index), openingStatus: "OPEN", personalQuota: { usedMinutes: 0, remainingMinutes: 120 }, teamQuotas: [], blocks: [] })),
};
const now = Date.parse(snapshot.serverTime);
const base: ReservationDetail = { id: "71", version: 0, subjectType: "TEAM", purpose: "REGULAR", teamId: "17", teamName: "가상 정규조 하나", instrumentCodes: ["DRUMS"], status: "CONFIRMED", phase: "FUTURE", isMine: true, canCancel: true, canChange: true, ownerUserId: null, creatorUserId: member.id, date: today, blockCode: "B03", startsAt: `${today}T14:00:00+09:00`, endsAt: `${today}T15:00:00+09:00`, usableFrom: `${today}T14:00:00+09:00`, quotaMinutes: 60, quotaExempt: false, originKind: "MANUAL", teamFaultWarnings: [], canceledAt: null, cancelReason: null, successorIds: [], dualAssetException: false };
const booking = (id: string, overrides: Partial<ReservationDetail> = {}): ReservationDetail => ({ ...base, id, ...overrides });
afterEach(() => { navigation.date = ""; vi.unstubAllGlobals(); });

function renderPage(current: Member | null, reservations: ReservationDetail[] = [], page: ComponentType = MySchedulePage) {
  const client = new QueryClient();
  client.setQueryData(memberQueryKey, current);
  const scope = directoryScope(current ?? member);
  client.setQueryData(scheduleQuery(scope).queryKey, snapshot);
  client.setQueryData(myReservationsQuery(scope, today, snapshot.bookableThrough).queryKey, reservations);
  const markup = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(page))));
  client.clear();
  return markup;
}

describe("current membership team schedule", () => {
  it("includes every current regular and additional team while excluding private, canceled, replaced and unrelated bookings", () => {
    const records = [base, booking("72", { teamId: "18", teamName: "가상 정규조 둘" }), booking("73", { teamId: "19", teamName: "가상 추가조", purpose: "ADDITIONAL" }),
      booking("private", { subjectType: "PERSONAL", teamId: null, purpose: "PERSONAL", ownerUserId: member.id }), booking("canceled", { status: "CANCELED" }),
      booking("replaced", { status: "REPLACED" }), booking("unrelated", { teamId: "99", teamName: "비소속 공연조" }), booking("missing-team", { teamId: null })];
    expect(myTeamSchedule(member, snapshot, records).flatMap((day) => day.reservations.map((reservation) => reservation.id))).toEqual(["71", "72", "73"]);
    expect(myTeamSchedule({ ...member, globalRole: "GLOBAL_ADMIN" }, snapshot, records).flatMap((day) => day.reservations.map((reservation) => reservation.id))).toEqual(["71", "72", "73"]);
  });
  it("uses only open materialized dates, includes today+14 and sorts by date then start without mutating the source", () => {
    const preparing = { ...snapshot, days: snapshot.days.map((day) => day.date === "2026-10-13" ? { ...day, openingStatus: "PREPARING" as const } : day) };
    const records = [booking("last", { date: snapshot.bookableThrough, startsAt: "2026-10-26T18:00:00+09:00", endsAt: "2026-10-26T19:30:00+09:00" }),
      base, booking("earlier", { startsAt: `${today}T12:00:00+09:00`, endsAt: `${today}T13:00:00+09:00` }),
      booking("preparing", { date: "2026-10-13" }), booking("yesterday", { date: "2026-10-11" }), booking("outside", { date: "2026-10-27" })];
    const original = structuredClone(records);
    const result = myTeamSchedule(member, preparing, records);
    expect(result.map((day) => day.date)).toEqual([today, snapshot.bookableThrough]);
    expect(result.flatMap((day) => day.reservations.map((reservation) => reservation.id))).toEqual(["earlier", "71", "last"]);
    expect(records).toEqual(original);
    expect(myTeamSchedule(member, preparing, records, "", "2026-10-13").map((day) => day.date)).toEqual([snapshot.bookableThrough]);
  });
  it("filters an individual team and drops schedules immediately when a membership disappears", () => {
    const records = [base, booking("72", { teamId: "18" })];
    expect(myTeamSchedule(member, snapshot, records, "18")[0].reservations.map((reservation) => reservation.id)).toEqual(["72"]);
    expect(myTeamSchedule(member, snapshot, records, "99")).toEqual([]);
    expect(myTeamSchedule({ ...member, teamMemberships: [] }, snapshot, records)).toEqual([]);
  });
});

describe("team schedule views and authorized entry", () => {
  it("shows team kind, manual/fixed source, server-clock phase, date links and Seoul B10 midnight", () => {
    const records = [booking("ended", { startsAt: `${today}T12:00:00+09:00`, endsAt: `${today}T13:00:00+09:00` }), base,
      booking("night", { teamId: "19", teamName: "가상 추가조", purpose: "ADDITIONAL", originKind: "FIXED", quotaMinutes: 0, blockCode: "B10", startsAt: `${today}T22:30:00+09:00`, endsAt: "2026-10-13T00:00:00+09:00" })];
    const markup = renderToStaticMarkup(createElement(MyTeamScheduleList, { days: myTeamSchedule(member, snapshot, records), now }));
    for (const label of ["정규조", "추가조", "일반 예약", "고정 배정", "예정", "진행 중", "종료", "10월 12일", "월", "22:30~24:00"]) expect(markup).toContain(label);
    expect(markup).toContain('href="/reservations?date=2026-10-12"');
    expect(markup).not.toContain("60분 한도 면제");
  });
  it("renders only a current member's team schedule and preserves navigation and the footer", () => {
    const markup = renderPage(member, [base, booking("other", { teamId: "99", teamName: "비소속 공연조" })]);
    expect(markup).toContain("내 공연조 일정 모아보기");
    expect(markup).toContain("내 공연조 전체");
    expect(markup).toContain("가상 정규조 하나");
    expect(markup).toContain("가상 정규조 둘");
    expect(markup).not.toContain("비소속 공연조");
    expect(markup).toContain('href="/my-schedule"');
    expect(markup).toContain("JEONGEUM · SENTIMENTAL MUSIC");
  });
  it("keeps anonymous/inactive users out and distinguishes no teams from no confirmed schedules", () => {
    for (const current of [null, { ...member, status: "INACTIVE" } as unknown as Member]) {
      const markup = renderPage(current, [base]);
      expect(markup).toContain("로그인이 필요해요");
      expect(markup).not.toContain('id="my-schedule-team"');
      expect(markup).not.toContain("가상 정규조 하나");
    }
    expect(renderPage({ ...member, teamMemberships: [] }, [base])).toContain("아직 소속된 공연조가 없어요");
    expect(renderPage(member)).toContain("열린 기간에 확정된 합주 일정이 없어요");
  });
  it("opens the linked reservation day while falling back safely for an out-of-range date", () => {
    navigation.date = snapshot.bookableThrough;
    expect(renderPage(member, [], ReservationsPage)).toContain(`id="reservation-date"`);
    expect(renderPage(member, [], ReservationsPage).match(/<input id="reservation-date"[^>]*>/)?.[0]).toContain(`value="${snapshot.bookableThrough}"`);
    navigation.date = "2026-10-27";
    expect(renderPage(member, [], ReservationsPage).match(/<input id="reservation-date"[^>]*>/)?.[0]).toContain(`value="${today}"`);
  });
});

describe("existing API and scoped query reuse", () => {
  it("queries the entire inclusive window through the existing mine endpoint without a new API", async () => {
    const fetchMock = vi.fn().mockResolvedValue(Response.json({ data: [base], meta: { serverTime: snapshot.serverTime, timeZone: "Asia/Seoul" } }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await getMyReservations(today, snapshot.bookableThrough)).toEqual([base]);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/v1/reservations?scope=mine&from=2026-10-12&to=2026-10-26");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({ method: "GET", credentials: "same-origin", cache: "no-store" });
  });
  it("shares mutation refresh and removes the old window cache after membership changes", async () => {
    const client = new QueryClient(); const scope = directoryScope(member);
    const key = myReservationsQuery(scope, today, snapshot.bookableThrough).queryKey;
    client.setQueryData(key, [base]); client.setQueryData(scheduleQuery(scope).queryKey, snapshot);
    expect(myReservationsQuery(scope, today, snapshot.bookableThrough).refetchInterval).toBe(20_000);
    await refreshReservations(client);
    expect(client.getQueryState(key)?.isInvalidated).toBe(true);
    const changed = { ...member, teamMemberships: member.teamMemberships.slice(1) };
    await pruneDirectoryCache(client, changed);
    expect(client.getQueryData(key)).toBeUndefined();
    client.clear();
  });
});
