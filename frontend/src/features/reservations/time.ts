import type { ReservationPhase, ScheduleBlock, ScheduleSnapshot } from "./types";

export function serverNow(snapshot: ScheduleSnapshot, clientNow: number): number {
  return Date.parse(snapshot.serverTime) + Math.max(0, clientNow - snapshot.receivedAtMillis);
}
export function seoulDate(timestamp: number): string {
  const parts = new Intl.DateTimeFormat("en", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit" }).formatToParts(timestamp);
  return `${parts.find((part) => part.type === "year")!.value}-${parts.find((part) => part.type === "month")!.value}-${parts.find((part) => part.type === "day")!.value}`;
}
export function addDate(date: string, days: number): string {
  const timestamp = Date.parse(`${date}T00:00:00Z`);
  return new Date(timestamp + days * 86_400_000).toISOString().slice(0, 10);
}
export function dateLabel(date: string): string { return new Intl.DateTimeFormat("ko-KR", { timeZone: "UTC", month: "long", day: "numeric", weekday: "short" }).format(new Date(`${date}T00:00:00Z`)); }
export function timeLabel(value: string): string { return new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(new Date(value)); }
export function blockLabel(block: Pick<ScheduleBlock, "startsAt" | "endsAt">): string {
  const start = timeLabel(block.startsAt); const end = timeLabel(block.endsAt);
  return `${start}~${end === "00:00" ? "24:00" : end}`;
}
export function phaseAt(startsAt: string, endsAt: string, now: number): ReservationPhase {
  return now >= Date.parse(endsAt) ? "ENDED" : now >= Date.parse(startsAt) ? "IN_PROGRESS" : "FUTURE";
}
export function remainingMinutes(block: Pick<ScheduleBlock, "startsAt" | "endsAt">, now: number): number {
  return Math.ceil(Math.max(0, Date.parse(block.endsAt) - Math.max(now, Date.parse(block.startsAt))) / 60_000);
}
