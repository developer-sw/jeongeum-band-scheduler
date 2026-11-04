import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { expect, it } from "vitest";
import type { Member } from "@/features/auth/types";
import type { ScheduleBlock, ScheduleDay } from "@/features/reservations/types";
import { Timetable } from "./timetable";
import { weekDates } from "./calendar";

it("keeps midnight selection and fault warnings while omitting instrument availability counts in both layouts", () => {
  const member: Member = { id: "1", loginId: "synthetic.member", displayName: "가상 회원", globalRole: "MEMBER", status: "ACTIVE", teamMemberships: [] };
  const block: ScheduleBlock = { blockCode: "B11", startsAt: "2026-11-05T00:00:00+09:00", endsAt: "2026-11-05T01:00:00+09:00", durationMinutes: 60, phase: "FUTURE", remainingMinutes: 60, canReservePersonal: true, canReserveTeam: true, availableInstrumentCodes: ["VOCAL"], faultyInstrumentCodes: ["DRUMS"], teamWarnings: ["DRUMS"], reservations: [] };
  const day: ScheduleDay = { date: "2026-11-05", openingStatus: "OPEN", personalQuota: { usedMinutes: 0, remainingMinutes: 120 }, teamQuotas: [], blocks: [block] };
  const html = renderToStaticMarkup(createElement(Timetable, { days: [day], week: weekDates(day.date), selectedDate: day.date, member, now: Date.parse("2026-11-04T23:30:00+09:00"), highlight: "", disabled: false, onDate: () => {}, onSelect: () => {} }));
  expect(html).not.toContain("이용 가능 악기");
  expect(html).toContain("00:00~01:00");
  expect(html.match(/data-diary-slot="2026-11-05_B11"/g)).toHaveLength(2);
  expect(html).toContain("+ 예약 선택");
  expect(html).toContain("고장 1종");
});
