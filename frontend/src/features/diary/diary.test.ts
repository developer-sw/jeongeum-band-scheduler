import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import type { Member } from "@/features/auth/types";
import type { ReservationSummary, ScheduleBlock, ScheduleDay } from "@/features/reservations/types";
import { inBookingRange, monthCells, moveMonth, visibleDays, weekDates, weekTarget } from "./calendar";
import { slotView } from "./slot";
import { Timetable } from "./timetable";

const member: Member = { id: "91", loginId: "synthetic.diary", displayName: "가상 회원", status: "ACTIVE", globalRole: "MEMBER", teamMemberships: [{ teamId: "71", teamName: "가상 정규조", kind: "REGULAR", role: "MEMBER" }, { teamId: "72", teamName: "가상 추가조", kind: "ADDITIONAL", role: "MEMBER" }] };
const block: ScheduleBlock = { blockCode: "B03", startsAt: "2026-10-12T14:00:00+09:00", endsAt: "2026-10-12T15:00:00+09:00", durationMinutes: 60, phase: "FUTURE", remainingMinutes: 60, canReservePersonal: true, canReserveTeam: true, availableInstrumentCodes: ["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"], faultyInstrumentCodes: [], teamWarnings: [], reservations: [] };
const day: ScheduleDay = { date: "2026-10-12", openingStatus: "OPEN", personalQuota: { usedMinutes: 0, remainingMinutes: 120 }, teamQuotas: [], blocks: [block] };
const personal: ReservationSummary = { id: "51", version: 0, subjectType: "PERSONAL", purpose: "PERSONAL", teamId: null, teamName: null, instrumentCodes: ["VOCAL"], status: "CONFIRMED", phase: "FUTURE", originKind: "MANUAL", quotaExempt: false, isMine: false, canCancel: false, canChange: false };
const now = Date.parse("2026-10-12T13:00:00+09:00");

describe("diary calendar uses canonical server dates", () => {
  it("starts Monday and includes Sunday across month/year boundaries", () => {
    expect(weekDates("2026-11-01")).toEqual(["2026-10-26", "2026-10-27", "2026-10-28", "2026-10-29", "2026-10-30", "2026-10-31", "2026-11-01"]);
    expect(weekDates("2027-01-01")[0]).toBe("2026-12-28");
  });
  it("includes leap day without overflowing when browsing from a month's last day", () => {
    const cells = monthCells("2028-02");
    expect(cells).toHaveLength(42);
    expect(cells).toContain("2028-02-29");
    expect(moveMonth("2026-01-31", 1)).toBe("2026-02");
    expect(moveMonth("2026-12", 1)).toBe("2027-01");
  });
  it("includes both server endpoints, excludes stale yesterday after midnight, and does not fabricate missing days", () => {
    expect(inBookingRange("2026-10-26", "2026-10-12", "2026-10-26")).toBe(true);
    expect(inBookingRange("2026-10-27", "2026-10-12", "2026-10-26")).toBe(false);
    const days = [{ ...day, date: "2026-10-11" }, day, { ...day, date: "2026-10-25", openingStatus: "PREPARING" as const }];
    expect(visibleDays(days, "2026-10-12", "2026-10-26").map((item) => item.date)).toEqual(["2026-10-12", "2026-10-25"]);
    expect(weekTarget(day.date, -1, visibleDays(days, "2026-10-12", "2026-10-26"))).toBeUndefined();
    expect(weekTarget(day.date, 1, days)).toBe("2026-10-25");
  });
});

describe("diary distinguishes occupancy from reservation permission", () => {
  it("counts occupied instruments rather than personal bookings, including the two-instrument exchange exception", () => {
    const shared = { ...block, reservations: [{ ...personal, instrumentCodes: ["VOCAL", "KEYBOARD"] as ReservationSummary["instrumentCodes"] }, { ...personal, id: "52", instrumentCodes: ["BASS"] as ReservationSummary["instrumentCodes"] }], canReserveTeam: false };
    const view = slotView(day, shared, member, now);
    expect(view.title).toBe("개인 악기 3/6 사용"); expect(view.kind).toBe("personal"); expect(view.available).toBe(true);
  });
  it("shows fully occupied personal resources and permits details without offering a reservation", () => {
    const full = { ...block, reservations: [{ ...personal, instrumentCodes: block.availableInstrumentCodes }], availableInstrumentCodes: [], canReservePersonal: false, canReserveTeam: false };
    const view = slotView(day, full, member, now);
    expect(view.title).toBe("개인 악기 모두 이용 중"); expect(view.actionLabel).toBe("점유 · 상세 보기");
  });
  it("highlights current memberships only and keeps fixed/manual occupancy visible under another team's filter", () => {
    const fixed = { ...personal, subjectType: "TEAM" as const, purpose: "REGULAR" as const, teamId: "71", teamName: "가상 정규조", originKind: "FIXED" as const, quotaExempt: true };
    const occupied = { ...block, reservations: [fixed], canReservePersonal: false, canReserveTeam: false };
    expect(slotView(day, occupied, member, now).myTeam).toBe(true);
    const filtered = slotView(day, occupied, member, now, "72");
    expect(filtered.myTeam).toBe(false); expect(filtered.title).toBe("가상 정규조"); expect(filtered.kind).toBe("fixed");
    expect(slotView(day, occupied, { ...member, teamMemberships: [] }, now).myTeam).toBe(false);
  });
  it("uses live server clock at progress/end boundaries and treats preparing dates as read only", () => {
    expect(slotView(day, block, member, Date.parse("2026-10-12T14:10:00+09:00")).timeStatus).toBe("진행 중 · 약 50분 남음");
    const ended = slotView(day, block, member, Date.parse(block.endsAt));
    expect(ended.available).toBe(false); expect(ended.kind).toBe("ended");
    const preparing = slotView({ ...day, openingStatus: "PREPARING" }, block, member, now);
    expect(preparing.title).toBe("고정 배정 반영 중"); expect(preparing.available).toBe(false);
  });
  it("does not mark canceled personal reservations as occupied or reveal names", () => {
    const view = slotView(day, { ...block, reservations: [{ ...personal, status: "CANCELED" }] }, member, now);
    expect(view.title).toBe("빈 시간"); expect(view.used).toBe(0);
    expect(view.mine).toBe(false);
  });
  it("renders outside-range cells explicitly, occupied detail buttons enabled, and the mobile day list from the same API", () => {
    const markup = renderToStaticMarkup(createElement(Timetable, { days: [{ ...day, blocks: [{ ...block, reservations: [personal], canReservePersonal: false, canReserveTeam: false }] }], week: weekDates(day.date), selectedDate: day.date, member, now, highlight: "", disabled: false, onDate: () => {}, onSelect: () => {} }));
    expect(markup).toContain("기간 밖"); expect(markup).toContain("개인 악기 1/6 사용");
    expect(markup).toContain("점유 · 상세 보기"); expect(markup).toContain("하루 1개 블록");
    expect(markup).not.toContain('class="diary-slot diary-slot-personal " disabled');
    expect(markup).not.toContain("synthetic.diary");
  });
});
