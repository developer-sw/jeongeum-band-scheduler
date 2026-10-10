"use client";

import Link from "next/link";
import { secondaryButton } from "@/components/auth-ui";
import { teamKindLabel } from "@/components/directory-ui";
import { blockLabel, dateLabel, phaseAt } from "@/features/reservations/time";
import type { ReservationDetail, ReservationPhase } from "@/features/reservations/types";
import type { TeamScheduleDay } from "./schedule";

const phaseNames: Record<ReservationPhase, string> = { FUTURE: "확정 · 예정", IN_PROGRESS: "진행 중", ENDED: "종료" };
const originNames: Record<ReservationDetail["originKind"], string> = { MANUAL: "일반 예약", FIXED: "고정 배정", CHANGE: "변경 예약", EXCHANGE: "교환 예약" };

export function MyTeamScheduleList({ days, now }: { days: TeamScheduleDay[]; now: number }) {
  return <div className="space-y-6">{days.map((day) => <section key={day.date} className="rounded-3xl border border-navy/15 p-5 sm:p-7" aria-labelledby={`my-schedule-${day.date}`}>
    <h2 id={`my-schedule-${day.date}`} className="text-lg font-bold"><time dateTime={day.date}>{dateLabel(day.date)}</time></h2>
    <ul className="mt-4 grid gap-4 md:grid-cols-2">{day.reservations.map((reservation) => <li key={reservation.id} className="min-w-0 rounded-2xl bg-paper p-5">
      <div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><h3 className="font-bold break-words">{reservation.teamName}</h3><p className="mt-1 text-xs leading-6 text-navy-soft">{teamKindLabel(reservation.purpose === "ADDITIONAL" ? "ADDITIONAL" : "REGULAR")} · {originNames[reservation.originKind]}</p></div><span className="rounded-full bg-white px-3 py-1 text-xs font-semibold">{phaseNames[phaseAt(reservation.startsAt, reservation.endsAt, now)]}</span></div>
      <p className="mt-4 text-base font-bold"><time dateTime={reservation.startsAt}>{blockLabel(reservation)}</time></p><p className="mt-1 text-xs leading-6 text-navy-soft">합주실 전체 독점 · 예약 #{reservation.id}{reservation.quotaExempt && " · 60분 한도 면제"}</p>
      <Link href={{ pathname: "/reservations", query: { date: reservation.date } }} className={`${secondaryButton} mt-4 inline-flex min-h-11 items-center`}>시간표·예약 확인</Link>
    </li>)}</ul>
  </section>)}</div>;
}
