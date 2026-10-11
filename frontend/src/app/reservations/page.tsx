"use client";

import { Suspense, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import type { Member } from "@/features/auth/types";
import { directoryScope } from "@/features/directory/queries";
import { BookingForm } from "@/features/reservations/booking-form";
import { ReservationDetailPanel, type ReservationPanelMode } from "@/features/reservations/detail-panel";
import { reservationErrorMessage } from "@/features/reservations/errors";
import { myReservationsQuery } from "@/features/reservations/queries";
import { blockLabel, dateLabel, phaseAt } from "@/features/reservations/time";
import type { ChangeReservationInput, CreateReservationInput, ReservationDetail, ScheduleBlock } from "@/features/reservations/types";
import { useBookingWorkspace } from "@/features/reservations/use-booking-workspace";
import { DesktopSchedule, MobileSchedule, ReservationCard, instrumentNames, subjectLabel } from "@/features/reservations/view";

function ReservationQueryState({ pending, error, retry }: { pending: boolean; error: unknown; retry: () => void }) {
  if (pending) return <p role="status" className="py-6 text-navy-soft">예약을 확인하고 있어요.</p>;
  return error ? <div className="space-y-4"><FormError message={reservationErrorMessage(error)} /><button type="button" className={secondaryButton} onClick={retry}>다시 불러오기</button></div> : null;
}

function ReservationBoard({ member, scope, initialDate, initialId, initialMode }: { member: Member; scope: string; initialDate: string; initialId: string; initialMode: ReservationPanelMode }) {
  const workspace = useBookingWorkspace(scope);
  const { query, schedule, now, today, action, busy, notice, setNotice } = workspace;
  const [selected, setSelected] = useState(initialDate); const [selectedBlock, setSelectedBlock] = useState<string | null>(null); const [historyDate, setHistoryDate] = useState("");
  const [detailSelection, setDetailSelection] = useState<{ id: string; mode: ReservationPanelMode } | null>(initialId ? { id: initialId, mode: initialMode } : null);
  const days = (schedule?.days ?? []).filter((day) => !today || day.date >= today);
  const date = days.some((day) => day.date === selected) ? selected : days[0]?.date ?? "";
  const selectedDay = days.find((day) => day.date === date);
  const block = selectedDay?.blocks.find((item) => item.blockCode === selectedBlock);
  const listDate = historyDate || date;
  const mine = useQuery({ ...myReservationsQuery(scope, listDate, listDate), enabled: Boolean(schedule && listDate) });
  const selectedIndex = days.findIndex((day) => day.date === date);
  const weekStart = Math.min(Math.floor(Math.max(0, selectedIndex) / 7) * 7, Math.max(0, days.length - 7));
  const week = days.slice(weekStart, weekStart + 7);

  function select(date: string, item: ScheduleBlock) { setSelected(date); setSelectedBlock(item.blockCode); setNotice(null); setDetailSelection(null); }
  function completed(result?: ReservationDetail) {
    if (result) { setDetailSelection({ id: result.id, mode: "DETAIL" }); setSelectedBlock(null); setSelected(result.date); setHistoryDate(result.date); }
  }
  async function create(input: CreateReservationInput) { completed(await workspace.create(input)); }
  async function cancel(reservation: ReservationDetail) { completed(await workspace.cancel(reservation)); }
  async function change(reservation: ReservationDetail, input: ChangeReservationInput) { if (!query.isError) completed(await workspace.change(reservation, input)); }
  async function retry() { completed(await workspace.retry()); }

  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">정음의 연습 시간</p><h1 className="mt-2 text-3xl font-extrabold">합주실 예약</h1><p className="mt-3 text-sm leading-7 text-navy-soft">팀은 합주실 전체, 개인은 악기 한 종류를 예약해요. 평일·주말 모두 같은 블록을 사용해요.</p>{schedule && <p className="mt-2 text-xs leading-6 text-navy-soft">한국 날짜 기준 {dateLabel(schedule.today)}부터 {dateLabel(schedule.bookableThrough)}까지 · 현재 상태는 20초마다 확인해요.</p>}<button type="button" className={`${secondaryButton} mt-4`} disabled={workspace.processing} onClick={() => void workspace.refresh()}>예약·점유 새로 확인</button></section>
    <FormError message={action.error} />{action.uncertain && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold">요청 결과를 아직 확인하지 못했어요</h2><p className="mt-2 text-sm leading-7">응답을 받기 전에 예약이 처리됐을 수 있어요. 최신 목록을 확인하거나 같은 요청을 직접 다시 보내 주세요. 새 요청은 이 결과를 확인한 뒤 진행해요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={workspace.processing} onClick={() => void retry()}>{workspace.processing ? "요청 확인 중…" : "같은 요청 결과 다시 확인"}</button></section>}{notice && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">{notice}</p>}
    <ReservationQueryState pending={query.isPending} error={query.error} retry={() => void query.refetch()} />
    {!query.error && schedule && selectedDay && <><section className="space-y-4 rounded-3xl bg-paper p-6 sm:p-8" aria-labelledby="date-title"><h2 id="date-title" className="text-xl font-bold">연습 날짜</h2><div className="flex flex-wrap items-end gap-3"><div className="min-w-0 flex-1"><Field id="reservation-date" label="날짜 선택"><input id="reservation-date" type="date" className={inputClass} value={date} min={days[0]?.date} max={schedule.bookableThrough} disabled={busy} onChange={(event) => { setSelected(event.target.value); setSelectedBlock(null); }} /></Field></div><button type="button" className={secondaryButton} disabled={busy || selectedIndex <= 0} onClick={() => { setSelected(days[selectedIndex - 1].date); setSelectedBlock(null); }}>이전 날</button><button type="button" className={secondaryButton} disabled={busy || selectedIndex >= days.length - 1} onClick={() => { setSelected(days[selectedIndex + 1].date); setSelectedBlock(null); }}>다음 날</button></div><p className="text-sm font-semibold">{dateLabel(date)}</p>{selectedDay.openingStatus !== "OPEN" && <p role="status" className="text-sm leading-7">고정 시간 반영 중이에요. 빈 시간 공개까지 기다려 주세요.</p>}<p className="text-xs leading-6 text-navy-soft">60분 예약은 개인별·팀별 하루 두 블록까지이며 서로 합산하지 않아요. 시작 후 취소한 60분도 사용 한도에 포함돼요.</p></section>
      <DesktopSchedule days={week} now={now} selectedDate={date} selectedBlockCode={selectedBlock ?? undefined} onSelect={(date, item) => { if (!busy) select(date, item); }} /><MobileSchedule day={selectedDay} instruments={schedule.instruments} now={now} selectedBlockCode={selectedBlock ?? undefined} onSelect={(date, item) => { if (!busy) select(date, item); }} />
      {block && <BookingForm key={`${scope}:${date}:${block.blockCode}`} member={member} schedule={schedule} day={selectedDay} block={block} now={now} disabled={busy} onCreate={(input) => void create(input)} />}
      {member.globalRole === "GLOBAL_ADMIN" && <section className={panelClass}><h2 className="text-xl font-bold">선택한 날짜의 관리자 예약 관리</h2><p className="mt-3 text-sm leading-7 text-navy-soft">내 예약 목록에 없는 개인·다른 팀 예약도 현재 권한이 허용하면 상세를 확인하고 변경·취소할 수 있어요.</p><ul className="mt-5 space-y-3">{selectedDay.blocks.flatMap((item) => item.reservations.filter((reservation) => !reservation.isMine && (reservation.canCancel || reservation.canChange) && phaseAt(item.startsAt, item.endsAt, now) !== "ENDED").map((reservation) => <li key={reservation.id} className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-paper p-4"><p className="text-sm leading-7">예약 #{reservation.id} · {subjectLabel(reservation)} · {blockLabel(item)} · {reservation.subjectType === "TEAM" ? "전체 독점" : instrumentNames(reservation.instrumentCodes)}</p><button type="button" className={secondaryButton} disabled={busy} onClick={() => setDetailSelection({ id: reservation.id, mode: "DETAIL" })}>상세·변경·취소 확인</button></li>))}</ul></section>}
    </>}
    {detailSelection && <ReservationDetailPanel key={`${scope}:${detailSelection.id}`} scope={scope} id={detailSelection.id} mode={detailSelection.mode} member={member} schedule={schedule} scheduleUnavailable={query.isError} now={now} disabled={busy} onMode={(mode) => setDetailSelection({ ...detailSelection, mode })} onCancel={(reservation) => void cancel(reservation)} onChange={(reservation, input) => void change(reservation, input)} onClose={() => setDetailSelection(null)} />}
    {schedule && <section className={panelClass} aria-labelledby="mine-title"><h2 id="mine-title" className="text-xl font-bold">내 개인·소속 팀 예약과 이력</h2><p className="mt-3 text-sm leading-7 text-navy-soft">현재 소속 팀의 예약도 함께 표시해요. 다른 팀원이 만든 예약은 해당 팀장·전체 관리자만 변경·취소할 수 있어요.</p><div className="mt-5"><Field id="reservation-history-date" label="예약·이력 조회 날짜" hint="지난 날짜를 선택하면 종료·취소 이력도 확인할 수 있어요."><input id="reservation-history-date" type="date" className={inputClass} value={listDate} max={schedule.bookableThrough} disabled={busy} onChange={(event) => setHistoryDate(event.target.value)} aria-describedby="reservation-history-date-hint" /></Field></div><div className="mt-5"><ReservationQueryState pending={mine.isPending} error={mine.error} retry={() => void mine.refetch()} />{!mine.error && mine.data && (mine.data.length ? <ul className="space-y-3">{mine.data.map((reservation) => <ReservationCard key={reservation.id} reservation={reservation} now={now} disabled={busy} onDetail={(id) => setDetailSelection({ id, mode: "DETAIL" })} onChange={(id) => setDetailSelection({ id, mode: "CHANGE" })} onCancel={(id) => setDetailSelection({ id, mode: "CANCEL" })} />)}</ul> : <p className="text-sm text-navy-soft">선택한 날짜에 내 개인·소속 팀 예약이 없어요.</p>)}</div></section>}
  </div>;
}

function ReservationWorkspace() {
  const { member } = useSession();
  const scope = directoryScope(member!);
  const search = useSearchParams();
  const initialDate = search.get("date") ?? "";
  const initialId = search.get("reservationId") ?? "";
  const initialMode: ReservationPanelMode = search.get("action") === "change" ? "CHANGE" : "DETAIL";
  return <ReservationBoard key={`${scope}:${initialDate}:${initialId}:${initialMode}`} member={member!} scope={scope} initialDate={initialDate} initialId={initialId} initialMode={initialMode} />;
}
export default function ReservationsPage() { return <AppShell><SessionGate><Suspense fallback={<p role="status" className="py-6 text-navy-soft">예약 날짜를 확인하고 있어요.</p>}><ReservationWorkspace /></Suspense></SessionGate></AppShell>; }
