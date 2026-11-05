import type { Member } from "@/features/auth/types";
import { blockLabel } from "@/features/reservations/time";
import type { InstrumentCode, ScheduleDay } from "@/features/reservations/types";
import { instrumentLabels, instrumentNames } from "@/features/reservations/view";

export interface CalendarReservationRow {
  key: string;
  time: string;
  label: string;
  myTeam: boolean;
  fixed: boolean;
}

/** Summarize only the confirmed occupancy already returned by the timetable. */
export function calendarReservationRows(day: ScheduleDay, member?: Pick<Member, "teamMemberships">): CalendarReservationRow[] {
  const myTeams = new Set(member?.teamMemberships.map((membership) => membership.teamId) ?? []);
  const rows: CalendarReservationRow[] = [];
  for (const block of [...day.blocks].sort((left, right) => Date.parse(left.startsAt) - Date.parse(right.startsAt))) {
    const reservations = block.reservations.filter((reservation) => reservation.status === "CONFIRMED");
    for (const reservation of reservations.filter((item) => item.subjectType === "TEAM")) {
      rows.push({ key: `${block.blockCode}:team:${reservation.id}`, time: blockLabel(block),
        label: reservation.teamName?.trim() || "팀 합주", myTeam: reservation.teamId !== null && myTeams.has(reservation.teamId),
        fixed: reservation.originKind === "FIXED" });
    }
    const personal = reservations.filter((item) => item.subjectType === "PERSONAL");
    if (personal.length) {
      const used = new Set(personal.flatMap((reservation) => reservation.instrumentCodes));
      const instruments = (Object.keys(instrumentLabels) as InstrumentCode[]).filter((code) => used.has(code));
      rows.push({ key: `${block.blockCode}:personal`, time: blockLabel(block),
        label: instruments.length ? `개인 · ${instrumentNames(instruments)}` : "개인 연습", myTeam: false, fixed: false });
    }
  }
  return rows;
}
