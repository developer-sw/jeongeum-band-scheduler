import { addDate } from "@/features/reservations/time";
import type { ScheduleDay } from "@/features/reservations/types";

export const weekdays = ["월", "화", "수", "목", "금", "토", "일"] as const;

// Calendar arithmetic stays in UTC; reservation dates themselves are Seoul dates
// supplied by the server. Browser timezone must not move a calendar cell.
export function weekDates(date: string): string[] {
  const weekday = new Date(`${date}T00:00:00Z`).getUTCDay();
  const monday = addDate(date, -((weekday + 6) % 7));
  return Array.from({ length: 7 }, (_, index) => addDate(monday, index));
}

export function monthCells(month: string): string[] {
  const first = `${month.slice(0, 7)}-01`;
  const start = weekDates(first)[0];
  return Array.from({ length: 42 }, (_, index) => addDate(start, index));
}

export function moveMonth(month: string, offset: number): string {
  const date = new Date(`${month.slice(0, 7)}-01T00:00:00Z`);
  date.setUTCMonth(date.getUTCMonth() + offset);
  return date.toISOString().slice(0, 7);
}

export function monthLabel(month: string): string {
  return `${Number(month.slice(0, 4))}년 ${Number(month.slice(5, 7))}월`;
}

export function inBookingRange(date: string, today: string, through: string): boolean {
  return date >= today && date <= through;
}

export function visibleDays(days: ScheduleDay[], today: string, through: string): ScheduleDay[] {
  return days.filter((day) => inBookingRange(day.date, today, through)).sort((a, b) => a.date.localeCompare(b.date));
}

export function weekTarget(selected: string, offset: number, days: ScheduleDay[]): string | undefined {
  const targetWeek = new Set(weekDates(addDate(selected, offset * 7)));
  return days.find((day) => targetWeek.has(day.date))?.date;
}
