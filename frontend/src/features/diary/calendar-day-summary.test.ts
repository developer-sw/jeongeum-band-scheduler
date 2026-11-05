import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { Member } from "@/features/auth/types";
import type { ReservationSummary, ScheduleBlock, ScheduleDay } from "@/features/reservations/types";
import { calendarReservationRows } from "./calendar-day-summary";
import { CalendarDaySummary } from "./calendar-day-summary-view";
import { CalendarPopup, CalendarSidebar } from "./calendar-view";

const member: Member = { id: "summary-member", loginId: "synthetic.summary", displayName: "요약 시험 회원", status: "ACTIVE", globalRole: "MEMBER", teamMemberships: [
  { teamId: "summary-regular", teamName: "가상 정규조", kind: "REGULAR", role: "MEMBER" },
  { teamId: "summary-additional", teamName: "가상 추가조", kind: "ADDITIONAL", role: "LEADER" },
] };
const personal: ReservationSummary = { id: "summary-personal", version: 0, subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, instrumentCodes: ["VOCAL"], status: "CONFIRMED", phase: "FUTURE", originKind: "MANUAL", quotaExempt: false, isMine: false, canCancel: false, canChange: false };
const regular: ReservationSummary = { ...personal, id: "summary-regular-reservation", subjectType: "TEAM", purpose: "REGULAR", teamId: "summary-regular", teamName: "가상 정규조", originKind: "FIXED" };
const additional: ReservationSummary = { ...regular, id: "summary-additional-reservation", purpose: "ADDITIONAL", teamId: "summary-additional", teamName: "가상 추가조", originKind: "MANUAL" };
const anotherTeam: ReservationSummary = { ...regular, id: "summary-another-reservation", teamId: "summary-another", teamName: "다른 가상조", originKind: "MANUAL" };
const block: ScheduleBlock = { blockCode: "B01", startsAt: "2026-11-04T12:00:00+09:00", endsAt: "2026-11-04T13:00:00+09:00", durationMinutes: 60, phase: "FUTURE", remainingMinutes: 60, canReservePersonal: true, canReserveTeam: true, availableInstrumentCodes: ["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"], faultyInstrumentCodes: [], teamWarnings: [], reservations: [] };
const day: ScheduleDay = { date: "2026-11-04", openingStatus: "OPEN", personalQuota: { usedMinutes: 0, remainingMinutes: 120 }, teamQuotas: [], blocks: [block] };
const occupiedDay: ScheduleDay = { ...day, blocks: [
  { ...block, reservations: [regular] },
  { ...block, blockCode: "B02", startsAt: "2026-11-04T13:00:00+09:00", endsAt: "2026-11-04T14:00:00+09:00", reservations: [additional] },
  { ...block, blockCode: "B03", startsAt: "2026-11-04T14:00:00+09:00", endsAt: "2026-11-04T15:00:00+09:00", reservations: [anotherTeam] },
] };

describe("selected calendar date reservation summary", () => {
  it("uses actual start times, rather than appended block codes, and keeps the final end at 24:00", () => {
    const early = { ...block, blockCode: "B11" as ScheduleBlock["blockCode"], startsAt: "2026-11-04T00:00:00+09:00", endsAt: "2026-11-04T01:00:00+09:00", reservations: [personal] };
    const late: ScheduleBlock = { ...block, blockCode: "B10", startsAt: "2026-11-04T22:30:00+09:00", endsAt: "2026-11-05T00:00:00+09:00", durationMinutes: 90, reservations: [regular] };
    expect(calendarReservationRows({ ...day, blocks: [late, { ...block, reservations: [regular] }, early] }, member).map((row) => row.time)).toEqual(["00:00~01:00", "12:00~13:00", "22:30~24:00"]);
  });

  it("groups shared personal practice and the two-instrument exception in one concise row without claimant identity", () => {
    const shared = { ...block, reservations: [
      { ...personal, instrumentCodes: ["KEYBOARD", "VOCAL"] as ReservationSummary["instrumentCodes"], originKind: "EXCHANGE" as const },
      { ...personal, id: "another-personal", instrumentCodes: ["BASS"] as ReservationSummary["instrumentCodes"], isMine: true },
    ] };
    expect(calendarReservationRows({ ...day, blocks: [shared] }, member)).toEqual([
      { key: "B01:personal", time: "12:00~13:00", label: "개인 · 보컬 · 건반 · 베이스", myTeam: false, fixed: false },
    ]);
    const markup = renderToStaticMarkup(createElement(CalendarDaySummary, { selected: day.date, day: { ...day, blocks: [shared] }, member }));
    expect(markup).not.toContain("summary-personal");
    expect(markup).not.toContain("another-personal");
    expect(markup).not.toContain(member.loginId);
    expect(markup).not.toContain("내 공연조");
  });

  it("includes confirmed past bookings but excludes canceled and replaced history and empty blocks", () => {
    const rows = calendarReservationRows({ ...day, blocks: [{ ...block, reservations: [
      { ...regular, phase: "ENDED" }, { ...additional, status: "CANCELED" }, { ...anotherTeam, status: "REPLACED" },
    ] }, { ...block, blockCode: "B02", reservations: [] }] }, member);
    expect(rows).toHaveLength(1);
    expect(rows[0]).toMatchObject({ label: "가상 정규조", fixed: true, myTeam: true });
  });

  it("highlights every current membership by ID, never another team's matching name or a personal reservation", () => {
    const rows = calendarReservationRows(occupiedDay, member);
    expect(rows.map((row) => row.myTeam)).toEqual([true, true, false]);
    expect(calendarReservationRows(occupiedDay).every((row) => !row.myTeam)).toBe(true);
    expect(calendarReservationRows(occupiedDay, { teamMemberships: [] }).every((row) => !row.myTeam)).toBe(true);
    expect(calendarReservationRows({ ...day, blocks: [{ ...block, reservations: [{ ...anotherTeam, teamName: regular.teamName, isMine: true }] }] }, member)[0].myTeam).toBe(false);
  });

  it.each([CalendarSidebar, CalendarPopup])("replaces the old calendar instructions with the same booked-date summary in %s", (Calendar) => {
    const markup = renderToStaticMarkup(createElement(Calendar, { selected: day.date, today: day.date, through: "2026-12-04", days: [occupiedDay], member, disabled: false, onSelect: () => {}, onUnavailable: () => {} }));
    expect(markup).toContain('aria-label="11월 4일 (수) 예약 요약"');
    expect(markup).toContain("12:00~13:00");
    expect(markup).toContain("가상 추가조");
    expect(markup.match(/class="diary-calendar-reservation my-team"/g)).toHaveLength(2);
    expect(markup).toContain('class="diary-calendar-reservation-badge">고정');
    expect(markup).not.toContain("서울 기준 오늘부터");
    expect(markup).not.toContain("이전 기록은 예약·이력에서 확인해요.");
  });

  it("switches summaries with the selected date using the existing schedule, without showing another day's bookings", () => {
    const nextDay = { ...day, date: "2026-11-05" };
    const markup = renderToStaticMarkup(createElement(CalendarSidebar, { selected: nextDay.date, today: day.date, through: "2026-12-04", days: [occupiedDay, nextDay], member, disabled: false, onSelect: () => {}, onUnavailable: () => {} }));
    expect(markup).toContain('aria-label="11월 5일 (목) 예약 요약"');
    expect(markup).toContain("예약된 시간이 없어요.");
    expect(markup).not.toContain("가상 정규조");
    expect(markup).not.toContain("12:00~13:00");
  });

  it("does not present an unopened or missing date as empty and labels stale cached bookings", () => {
    const preparing = renderToStaticMarkup(createElement(CalendarDaySummary, { selected: day.date, day: { ...occupiedDay, openingStatus: "PREPARING" }, member }));
    expect(preparing).toContain("고정 배정을 반영하고 있어요.");
    expect(preparing).not.toContain("예약된 시간이 없어요.");
    expect(preparing).not.toContain("가상 정규조");
    const missing = renderToStaticMarkup(createElement(CalendarDaySummary, { selected: day.date, member }));
    expect(missing).toContain("선택한 날짜의 예약을 확인하고 있어요.");
    const stale = renderToStaticMarkup(createElement(CalendarDaySummary, { selected: day.date, day: occupiedDay, member, stale: true, refreshing: true }));
    expect(stale).toContain('aria-busy="true"');
    expect(stale).toContain("마지막으로 확인한 예약이에요.");
    expect(stale).toContain("가상 정규조");
  });
});
