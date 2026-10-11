"use client";

import { secondaryButton } from "@/components/auth-ui";
import Link from "next/link";
import { blockLabel, dateLabel, phaseAt, remainingMinutes } from "./time";
import type { Instrument, InstrumentCode, ReservationDetail, ReservationSummary, ScheduleBlock, ScheduleDay } from "./types";

export const instrumentLabels: Record<InstrumentCode, string> = { VOCAL: "보컬", KEYBOARD: "건반", ELECTRIC_GUITAR: "일렉기타", BASS: "베이스", DRUMS: "드럼", ACOUSTIC_GUITAR: "통기타" };
export const instrumentNames = (codes: InstrumentCode[]) => codes.map((code) => instrumentLabels[code]).join(" · ");
export const subjectLabel = (reservation: ReservationSummary) => reservation.subjectType === "TEAM" ? reservation.teamName ?? "팀 합주" : "개인 연습";
export const phaseLabels = { FUTURE: "시작 전", IN_PROGRESS: "진행 중", ENDED: "종료" } as const;

export function quotaHistoryLabel(reservation: ReservationDetail): string {
  if (reservation.status === "REPLACED") return "원본 예약 기록 보존 · 현재 한도 합산 없음";
  if (reservation.quotaExempt) return "60분 한도 면제 (관리자 원래 배정)";
  if (reservation.quotaMinutes === 0) return `${reservation.status === "CANCELED" ? "취소 · " : ""}60분 한도 합산 없음 (90분 블록)`;
  if (reservation.status === "CANCELED" && reservation.canceledAt) {
    return Date.parse(reservation.canceledAt) < Date.parse(reservation.startsAt)
      ? "시작 전 취소 · 해당 60분 한도 복원"
      : `시작 후 취소 · 당일 60분 한도 ${reservation.quotaMinutes}분 유지`;
  }
  return `60분 블록 한도 소비 ${reservation.quotaMinutes}분`;
}

export function cancellationNotice(reservation: ReservationDetail, now: number): string {
  const phase = phaseAt(reservation.startsAt, reservation.endsAt, now);
  if (phase === "ENDED") return "종료된 예약은 이용 이력으로 보존하며 취소할 수 없어요.";
  const occupancy = phase === "IN_PROGRESS" ? "진행 중 예약을 취소하면 남은 점유를 해제해요." : "시작 전에 취소하면 해당 예약의 점유를 해제해요.";
  const quota = reservation.quotaExempt ? "관리자 원래 배정은 60분 한도 면제예요."
    : reservation.quotaMinutes === 0 ? "90분 블록은 60분 한도에 합산하지 않아요."
    : phase === "IN_PROGRESS" ? "이미 시작한 60분 블록의 당일 사용 한도는 유지돼요." : "해당 60분 블록 한도를 복원해요.";
  return `${occupancy} ${quota}`;
}

export function ResourceList({ block, instruments }: { block: ScheduleBlock; instruments: Instrument[] }) {
  return <ul className="mt-4 grid grid-cols-2 gap-2 text-xs sm:grid-cols-3">{instruments.map((instrument) => {
    const occupied = block.reservations.some((reservation) => reservation.instrumentCodes.includes(instrument.code));
    const faulty = block.faultyInstrumentCodes.includes(instrument.code) || instrument.status === "FAULTY";
    return <li key={instrument.code} className={`rounded-lg px-3 py-2 ${occupied ? "bg-navy/10" : faulty ? "bg-red-50 text-red-800" : "bg-yellow/15"}`}><span className="block font-semibold">{instrument.name}</span><span className="mt-1 block">{occupied ? "예약됨" : "비어 있음"}{faulty && " · 고장"}</span></li>;
  })}</ul>;
}

function BlockSummary({ block, now }: { block: ScheduleBlock; now: number }) {
  const team = block.reservations.find((reservation) => reservation.subjectType === "TEAM");
  const phase = phaseAt(block.startsAt, block.endsAt, now);
  return <><p className="font-semibold break-words">{team ? `${subjectLabel(team)} · 전체 독점` : block.reservations.length ? `개인 ${block.reservations.length}건 · 악기별 공유` : "빈 시간"}</p>{team?.originKind === "FIXED" && <p className="mt-2 text-xs font-bold">관리자 고정 배정{team.quotaExempt && " · 60분 한도 면제"}</p>}<p className="mt-1 text-xs leading-6 text-navy-soft">{phaseLabels[phase]}{phase === "IN_PROGRESS" && ` · 잔여 약 ${remainingMinutes(block, now)}분`}</p></>;
}

export function MobileSchedule({ day, instruments, now, selectedBlockCode, onSelect }: { day: ScheduleDay; instruments: Instrument[]; now: number; selectedBlockCode?: string; onSelect: (date: string, block: ScheduleBlock) => void }) {
  return <section className="space-y-4 lg:hidden" aria-label="선택한 날짜의 시간표">{day.blocks.map((block) => {
    const ended = phaseAt(block.startsAt, block.endsAt, now) === "ENDED";
    const unavailable = day.openingStatus !== "OPEN" || ended || (!block.canReservePersonal && !block.canReserveTeam);
    return <article key={block.blockCode} className={`rounded-2xl border p-5 ${selectedBlockCode === block.blockCode ? "border-yellow bg-yellow/5" : "border-navy/15"}`}><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="font-bold">{blockLabel(block)}</h3><span className="rounded-full bg-paper px-3 py-1 text-xs">{block.durationMinutes}분</span></div><div className="mt-3"><BlockSummary block={block} now={now} /></div><ResourceList block={block} instruments={instruments} /><button type="button" className={`${secondaryButton} mt-4 w-full`} disabled={unavailable} onClick={() => onSelect(day.date, block)}>{day.openingStatus !== "OPEN" ? "고정 시간 반영 중" : ended ? "종료된 시간" : unavailable ? "현재 예약 불가" : `${blockLabel(block)} 예약 선택`}</button></article>;
  })}</section>;
}

export function DesktopSchedule({ days, now, selectedDate, selectedBlockCode, onSelect }: { days: ScheduleDay[]; now: number; selectedDate: string; selectedBlockCode?: string; onSelect: (date: string, block: ScheduleBlock) => void }) {
  if (!days.length) return null;
  return <section className="hidden lg:block" aria-label="주간 시간표"><div className="grid gap-2" style={{ gridTemplateColumns: `5rem repeat(${days.length}, minmax(0, 1fr))` }}><div className="p-2 text-xs font-semibold">시간</div>{days.map((day) => <div key={day.date} className={`rounded-xl p-3 text-center text-xs font-bold ${day.date === selectedDate ? "bg-yellow/40" : "bg-paper"}`}>{dateLabel(day.date)}</div>)}
    {days[0].blocks.map((reference) => <div key={reference.blockCode} className="contents"><div className="p-2 text-xs leading-6 font-semibold">{blockLabel(reference).split("~").map((value, index) => <span key={value} className="block">{index ? "~" : ""}{value}</span>)}<span className="block font-normal text-navy-soft">{reference.durationMinutes}분</span></div>{days.map((day) => {
      const block = day.blocks.find((item) => item.blockCode === reference.blockCode)!;
      const ended = phaseAt(block.startsAt, block.endsAt, now) === "ENDED";
      const unavailable = day.openingStatus !== "OPEN" || ended || (!block.canReservePersonal && !block.canReserveTeam);
      const selected = day.date === selectedDate && block.blockCode === selectedBlockCode;
      return <div key={day.date} className={`min-w-0 rounded-xl border p-3 text-xs ${selected ? "border-yellow bg-yellow/10" : "border-navy/15"}`}><BlockSummary block={block} now={now} /><p className="mt-2 leading-6">예약 가능 악기 {block.availableInstrumentCodes.length}/6{block.faultyInstrumentCodes.length > 0 && <span className="block text-red-800">고장 {block.faultyInstrumentCodes.length}종</span>}</p><button type="button" className="mt-2 min-h-11 w-full rounded-lg bg-paper px-2 py-2 font-bold disabled:opacity-50" disabled={unavailable} onClick={() => onSelect(day.date, block)} aria-label={`${dateLabel(day.date)} ${blockLabel(block)} 예약 선택`}>{day.openingStatus !== "OPEN" ? "준비 중" : ended ? "종료" : unavailable ? "예약 불가" : "선택"}</button></div>;
    })}</div>)}
  </div></section>;
}

export function ReservationCard({ reservation, now, disabled, onCancel, onChange, onDetail }: { reservation: ReservationDetail; now: number; disabled: boolean; onCancel: (id: string) => void; onChange?: (id: string) => void; onDetail?: (id: string) => void }) {
  const phase = phaseAt(reservation.startsAt, reservation.endsAt, now);
  return <li className="rounded-2xl border border-navy/15 p-5"><div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><h3 className="font-bold break-words">{subjectLabel(reservation)}</h3><p className="mt-2 text-sm">{dateLabel(reservation.date)} · {blockLabel(reservation)}</p><p className="mt-1 text-sm break-words">{reservation.subjectType === "TEAM" ? "합주실 전체 독점" : instrumentNames(reservation.instrumentCodes)}</p><p className="mt-2 text-xs leading-6 text-navy-soft">예약 #{reservation.id} · {reservation.status === "CANCELED" ? "취소됨" : reservation.status === "REPLACED" ? "이전 예약 이력" : phaseLabels[phase]} · {quotaHistoryLabel(reservation)}</p>{reservation.status === "CONFIRMED" && phase === "IN_PROGRESS" && <p className="mt-2 text-xs leading-6">이용 가능 약 {remainingMinutes(reservation, now)}분 · 이용 시작 {new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(new Date(reservation.usableFrom))}</p>}{reservation.teamFaultWarnings.length > 0 && <p className="mt-2 text-xs leading-6 text-red-800">고장 안내: {instrumentNames(reservation.teamFaultWarnings)}</p>}</div><div className="flex flex-wrap gap-2">{onDetail ? <button type="button" className={secondaryButton} disabled={disabled} onClick={() => onDetail(reservation.id)}>상세·이력</button> : <Link className={secondaryButton} href={`/reservations?date=${reservation.date}&reservationId=${encodeURIComponent(reservation.id)}`}>상세·이력</Link>}{onChange && reservation.status === "CONFIRMED" && reservation.canChange && phase === "FUTURE" && <button type="button" className={secondaryButton} disabled={disabled} onClick={() => onChange(reservation.id)} aria-label={`예약 #${reservation.id} 변경`}>예약 변경</button>}{reservation.status === "CONFIRMED" && reservation.canCancel && phase !== "ENDED" && <button type="button" className={secondaryButton} disabled={disabled} onClick={() => onCancel(reservation.id)} aria-label={`예약 #${reservation.id} 취소 확인`}>취소 확인</button>}</div></div></li>;
}
