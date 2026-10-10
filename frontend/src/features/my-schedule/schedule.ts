import type { Member } from "@/features/auth/types";
import type { ReservationDetail, ScheduleSnapshot } from "@/features/reservations/types";

export interface TeamScheduleDay { date: string; reservations: ReservationDetail[] }

export function myTeamSchedule(member: Member, schedule: ScheduleSnapshot, reservations: ReservationDetail[], teamId = "", today = schedule.today): TeamScheduleDay[] {
  const memberships = new Set(member.teamMemberships.map((membership) => membership.teamId));
  const openDates = new Set(schedule.days.filter((day) => day.openingStatus === "OPEN" && day.date >= schedule.today && day.date >= today && day.date <= schedule.bookableThrough).map((day) => day.date));
  const matching = reservations.filter((reservation) => reservation.subjectType === "TEAM" && reservation.status === "CONFIRMED"
    && reservation.teamId !== null && memberships.has(reservation.teamId) && (!teamId || reservation.teamId === teamId) && openDates.has(reservation.date))
    .sort((a, b) => a.date.localeCompare(b.date) || Date.parse(a.startsAt) - Date.parse(b.startsAt) || a.id.localeCompare(b.id));
  const days: TeamScheduleDay[] = [];
  for (const reservation of matching) {
    const last = days.at(-1);
    if (last?.date === reservation.date) last.reservations.push(reservation);
    else days.push({ date: reservation.date, reservations: [reservation] });
  }
  return days;
}
