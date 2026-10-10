"use client";

import { Suspense, useEffect, useRef, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import type { Member } from "@/features/auth/types";
import { directoryScope } from "@/features/directory/queries";
import { bookingInput } from "@/features/reservations/booking";
import { reservationErrorMessage } from "@/features/reservations/errors";
import { myReservationsQuery, refreshReservations, reservationQuery, scheduleQuery } from "@/features/reservations/queries";
import { blockLabel, dateLabel, phaseAt, remainingMinutes, seoulDate } from "@/features/reservations/time";
import type { CreateReservationInput, InstrumentCode, ReservationDetail, ScheduleBlock, ScheduleDay, ScheduleSnapshot } from "@/features/reservations/types";
import { useReservationAction } from "@/features/reservations/use-reservation-action";
import { useServerClock } from "@/features/reservations/use-server-clock";
import { DesktopSchedule, MobileSchedule, ReservationCard, ResourceList, cancellationNotice, instrumentNames, subjectLabel } from "@/features/reservations/view";

function ReservationQueryState({ pending, error, retry }: { pending: boolean; error: unknown; retry: () => void }) {
  if (pending) return <p role="status" className="py-6 text-navy-soft">예약을 확인하고 있어요.</p>;
  return error ? <div className="space-y-4"><FormError message={reservationErrorMessage(error)} /><button type="button" className={secondaryButton} onClick={retry}>다시 불러오기</button></div> : null;
}

function BookingForm({ member, schedule, day, block, now, disabled, onCreate }: { member: Member; schedule: ScheduleSnapshot; day: ScheduleDay; block: ScheduleBlock; now: number; disabled: boolean; onCreate: (input: CreateReservationInput) => void }) {
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { title.current?.focus(); }, []);
  const [subject, setSubject] = useState("PERSONAL"); const [instrument, setInstrument] = useState(""); const [validation, setValidation] = useState<string | null>(null);
  const personal = subject === "PERSONAL";
  const phase = phaseAt(block.startsAt, block.endsAt, now);
  const quota = personal ? day.personalQuota : day.teamQuotas.find((item) => item.teamId === subject);
  const allowed = day.openingStatus === "OPEN" && phase !== "ENDED" && (personal ? block.canReservePersonal : block.canReserveTeam) && (block.durationMinutes === 90 || Boolean(quota && quota.remainingMinutes >= 60));
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const input = bookingInput(member, day, block, subject, instrument, now);
    if (typeof input === "string") { setValidation(input); return; }
    setValidation(null); onCreate(input);
  }
  return <section id="booking-confirmation" className="rounded-3xl border-2 border-yellow p-6 sm:p-8" aria-labelledby="booking-title"><h2 ref={title} id="booking-title" tabIndex={-1} className="text-xl font-bold">선택한 시간 예약</h2><p className="mt-3 text-sm leading-7">{dateLabel(day.date)} · {blockLabel(block)} · {block.durationMinutes}분</p><p className="mt-1 text-sm leading-7 text-navy-soft">{phase === "IN_PROGRESS" ? `이미 시작한 블록이에요. 지금부터 종료까지 약 ${remainingMinutes(block, now)}분 이용할 수 있어요.` : phase === "ENDED" ? "이미 종료된 블록이에요. 다른 시간을 선택해 주세요." : "정해진 블록 전체를 예약해요."}</p><ResourceList block={block} instruments={schedule.instruments} />
    <form method="post" onSubmit={submit} className="mt-6 space-y-5" aria-busy={disabled}><Field id="reservation-subject" label="연습 주체"><select id="reservation-subject" className={inputClass} value={subject} onChange={(event) => { setSubject(event.target.value); setValidation(null); }} disabled={disabled}><option value="PERSONAL">나의 개인 연습</option>{member.teamMemberships.map((membership) => <option key={membership.teamId} value={membership.teamId}>{membership.teamName} · {membership.kind === "REGULAR" ? "정규조" : "추가조"}</option>)}</select></Field>
      {personal ? <Field id="reservation-instrument" label="악기 한 종류" hint="같은 시간에 본인 개인 예약은 한 건만 가능해요."><select id="reservation-instrument" className={inputClass} value={block.availableInstrumentCodes.includes(instrument as InstrumentCode) ? instrument : ""} onChange={(event) => setInstrument(event.target.value)} disabled={disabled || !allowed} required aria-describedby="reservation-instrument-hint"><option value="">예약 가능한 악기 선택</option>{schedule.instruments.filter((item) => block.availableInstrumentCodes.includes(item.code)).map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}</select></Field> : <p className="rounded-xl bg-paper p-4 text-sm leading-7">정규조·추가조는 합주실 전체를 독점해요. 모든 악기를 함께 점유하며 다른 예약과 겹칠 수 없어요.</p>}
      <div className="rounded-xl bg-paper p-4 text-sm leading-7"><p>{personal ? "나의 개인 예약" : "선택한 팀 예약"} 60분 블록 사용량: {quota?.usedMinutes ?? 0}/120분 · 남은 한도 {quota?.remainingMinutes ?? 0}분</p><p className="mt-1">이번 예약의 한도 소비: {block.durationMinutes === 60 ? "60분" : "0분 (90분 블록은 별도)"}{phase === "IN_PROGRESS" && block.durationMinutes === 60 && " · 잔여 시간만 이용해도 60분 한 칸으로 계산해요."}</p></div>
      {!personal && block.teamWarnings.length > 0 && <p role="status" className="rounded-xl bg-red-50 p-4 text-sm leading-7 text-red-800">고장 악기: {instrumentNames(block.teamWarnings)}. 팀 예약은 가능하지만 고장 정보를 확인해 주세요.</p>}
      {!allowed && <p role="status" className="text-sm leading-7 text-navy-soft">현재 점유·개인 중복·선택한 주체의 한도·개방 상태에 따라 예약할 수 없는 시간이에요. 최신 시간표에서 다른 시간을 선택해 주세요.</p>}
      <FormError message={validation} /><button type="submit" className={`${primaryButton} w-full sm:w-auto`} disabled={disabled || !allowed || (personal && !block.availableInstrumentCodes.includes(instrument as InstrumentCode))}>{disabled ? "요청 확인 중…" : "예약 확정 요청"}</button><p className="text-xs leading-6 text-navy-soft">최종 점유·권한·한도는 서버에서 다시 검사해요. 2시간은 60분 예약 두 건으로 각각 처리해 주세요.</p>
    </form></section>;
}

function CancellationPanel({ scope, id, now, disabled, onCancel, onClose }: { scope: string; id: string; now: number; disabled: boolean; onCancel: (reservation: ReservationDetail) => void; onClose: () => void }) {
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { title.current?.focus(); }, []);
  const query = useQuery(reservationQuery(scope, id));
  const detail = query.data;
  const phase = detail ? phaseAt(detail.startsAt, detail.endsAt, now) : "ENDED";
  const allowed = Boolean(detail?.canCancel && detail.status === "CONFIRMED" && phase !== "ENDED");
  return <section className="rounded-3xl border border-yellow bg-yellow/10 p-6" aria-labelledby="cancel-title"><h2 ref={title} id="cancel-title" tabIndex={-1} className="text-xl font-bold">예약 취소 확인</h2><ReservationQueryState pending={query.isPending} error={query.error} retry={() => void query.refetch()} />{!query.error && detail && <><p className="mt-3 text-sm leading-7 break-words">예약 #{detail.id} · {subjectLabel(detail)}<br />{dateLabel(detail.date)} · {blockLabel(detail)} · {detail.subjectType === "TEAM" ? "합주실 전체" : instrumentNames(detail.instrumentCodes)}</p><p className="mt-3 text-sm leading-7">{cancellationNotice(detail, now)}</p>{detail.originKind === "FIXED" && <p className="mt-2 text-sm leading-7">이 날짜의 회차만 취소하며 다음 주 고정 배정은 유지해요.</p>}{!allowed && <p role="status" className="mt-3 text-sm leading-7">현재 권한이나 예약 상태에 따라 취소할 수 없어요. 목록을 새로 확인해 주세요.</p>}<div className="mt-5 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={disabled || !allowed} onClick={() => onCancel(detail)}>{disabled ? "요청 확인 중…" : "예약 취소 확정"}</button><button type="button" className={secondaryButton} disabled={disabled} onClick={onClose}>닫기</button></div></>}{query.error && <button type="button" className={`${secondaryButton} mt-4`} onClick={onClose}>닫기</button>}</section>;
}

function ReservationBoard({ member, scope, initialDate }: { member: Member; scope: string; initialDate: string }) {
  const client = useQueryClient(); const query = useQuery(scheduleQuery(scope)); const now = useServerClock(query.data); const action = useReservationAction();
  const [selected, setSelected] = useState(initialDate); const [selectedBlock, setSelectedBlock] = useState<string | null>(null); const [historyDate, setHistoryDate] = useState("");
  const [cancelId, setCancelId] = useState<string | null>(null); const [notice, setNotice] = useState<string | null>(null);
  const schedule = query.data;
  const today = schedule && now ? seoulDate(now) : schedule?.today;
  const days = (schedule?.days ?? []).filter((day) => !today || day.date >= today);
  const date = days.some((day) => day.date === selected) ? selected : days[0]?.date ?? "";
  const selectedDay = days.find((day) => day.date === date);
  const block = selectedDay?.blocks.find((item) => item.blockCode === selectedBlock);
  const listDate = historyDate || date;
  const mine = useQuery({ ...myReservationsQuery(scope, listDate, listDate), enabled: Boolean(schedule && listDate) });
  const busy = action.pending || action.uncertain;
  const selectedIndex = days.findIndex((day) => day.date === date);
  const weekStart = Math.min(Math.floor(Math.max(0, selectedIndex) / 7) * 7, Math.max(0, days.length - 7));
  const week = days.slice(weekStart, weekStart + 7);

  useEffect(() => {
    if (today && schedule && today !== schedule.today) void client.invalidateQueries({ queryKey: ["reservations", scope, "schedule"] });
  }, [client, today, schedule, scope]);

  function select(date: string, item: ScheduleBlock) { setSelected(date); setSelectedBlock(item.blockCode); setNotice(null); setCancelId(null); }
  async function completed(result?: ReservationDetail) {
    if (result) { setNotice(result.status === "CANCELED" ? `예약 #${result.id}의 취소 상태를 확인했어요.` : `예약 #${result.id}이 확정됐어요. ${dateLabel(result.date)} ${blockLabel(result)}`); setCancelId(null); setSelectedBlock(null); }
    await refreshReservations(client);
  }
  async function create(input: CreateReservationInput) { setNotice(null); await completed(await action.run({ kind: "CREATE", input })); }
  async function cancel(reservation: ReservationDetail) { setNotice(null); await completed(await action.run({ kind: "CANCEL", reservationId: reservation.id, input: { expectedVersion: reservation.version } })); }
  async function retry() { await completed(await action.retry()); }

  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">정음의 연습 시간</p><h1 className="mt-2 text-3xl font-extrabold">합주실 예약</h1><p className="mt-3 text-sm leading-7 text-navy-soft">팀은 합주실 전체, 개인은 악기 한 종류를 예약해요. 평일·주말 모두 같은 블록을 사용해요.</p>{schedule && <p className="mt-2 text-xs leading-6 text-navy-soft">한국 날짜 기준 {dateLabel(schedule.today)}부터 {dateLabel(schedule.bookableThrough)}까지 · 현재 상태는 20초마다 확인해요.</p>}<button type="button" className={`${secondaryButton} mt-4`} disabled={action.pending} onClick={() => void refreshReservations(client)}>예약·점유 새로 확인</button></section>
    <FormError message={action.error} />{action.uncertain && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold">요청 결과를 아직 확인하지 못했어요</h2><p className="mt-2 text-sm leading-7">응답을 받기 전에 예약이 처리됐을 수 있어요. 최신 목록을 확인하거나 같은 요청을 직접 다시 보내 주세요. 새 요청은 이 결과를 확인한 뒤 진행해요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={action.pending} onClick={() => void retry()}>{action.pending ? "요청 확인 중…" : "같은 요청 결과 다시 확인"}</button></section>}{notice && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">{notice}</p>}
    <ReservationQueryState pending={query.isPending} error={query.error} retry={() => void query.refetch()} />
    {!query.error && schedule && selectedDay && <><section className="space-y-4 rounded-3xl bg-paper p-6 sm:p-8" aria-labelledby="date-title"><h2 id="date-title" className="text-xl font-bold">연습 날짜</h2><div className="flex flex-wrap items-end gap-3"><div className="min-w-0 flex-1"><Field id="reservation-date" label="날짜 선택"><input id="reservation-date" type="date" className={inputClass} value={date} min={days[0]?.date} max={schedule.bookableThrough} disabled={busy} onChange={(event) => { setSelected(event.target.value); setSelectedBlock(null); }} /></Field></div><button type="button" className={secondaryButton} disabled={busy || selectedIndex <= 0} onClick={() => { setSelected(days[selectedIndex - 1].date); setSelectedBlock(null); }}>이전 날</button><button type="button" className={secondaryButton} disabled={busy || selectedIndex >= days.length - 1} onClick={() => { setSelected(days[selectedIndex + 1].date); setSelectedBlock(null); }}>다음 날</button></div><p className="text-sm font-semibold">{dateLabel(date)}</p>{selectedDay.openingStatus !== "OPEN" && <p role="status" className="text-sm leading-7">고정 시간 반영 중이에요. 빈 시간 공개까지 기다려 주세요.</p>}<p className="text-xs leading-6 text-navy-soft">60분 예약은 개인별·팀별 하루 두 블록까지이며 서로 합산하지 않아요. 시작 후 취소한 60분도 사용 한도에 포함돼요.</p></section>
      <DesktopSchedule days={week} now={now} selectedDate={date} selectedBlockCode={selectedBlock ?? undefined} onSelect={(date, item) => { if (!busy) select(date, item); }} /><MobileSchedule day={selectedDay} instruments={schedule.instruments} now={now} selectedBlockCode={selectedBlock ?? undefined} onSelect={(date, item) => { if (!busy) select(date, item); }} />
      {block && <BookingForm key={`${scope}:${date}:${block.blockCode}`} member={member} schedule={schedule} day={selectedDay} block={block} now={now} disabled={busy} onCreate={(input) => void create(input)} />}
      {member.globalRole === "GLOBAL_ADMIN" && <section className={panelClass}><h2 className="text-xl font-bold">선택한 날짜의 관리자 취소</h2><p className="mt-3 text-sm leading-7 text-navy-soft">내 예약 목록에 없는 개인·다른 팀 예약도 현재 권한이 허용하면 상세를 확인하고 취소할 수 있어요.</p><ul className="mt-5 space-y-3">{selectedDay.blocks.flatMap((item) => item.reservations.filter((reservation) => !reservation.isMine && reservation.canCancel && phaseAt(item.startsAt, item.endsAt, now) !== "ENDED").map((reservation) => <li key={reservation.id} className="flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-paper p-4"><p className="text-sm leading-7">예약 #{reservation.id} · {subjectLabel(reservation)} · {blockLabel(item)} · {reservation.subjectType === "TEAM" ? "전체 독점" : instrumentNames(reservation.instrumentCodes)}</p><button type="button" className={secondaryButton} disabled={busy} onClick={() => setCancelId(reservation.id)}>상세·취소 확인</button></li>))}</ul></section>}
    </>}
    {cancelId && <CancellationPanel key={`${scope}:${cancelId}`} scope={scope} id={cancelId} now={now} disabled={busy} onCancel={(reservation) => void cancel(reservation)} onClose={() => setCancelId(null)} />}
    {schedule && <section className={panelClass} aria-labelledby="mine-title"><h2 id="mine-title" className="text-xl font-bold">내 개인·소속 팀 예약과 이력</h2><p className="mt-3 text-sm leading-7 text-navy-soft">현재 소속 팀의 예약도 함께 표시해요. 다른 팀원이 만든 예약은 해당 팀장·전체 관리자만 취소할 수 있어요.</p><div className="mt-5"><Field id="reservation-history-date" label="예약·이력 조회 날짜" hint="지난 날짜를 선택하면 종료·취소 이력도 확인할 수 있어요."><input id="reservation-history-date" type="date" className={inputClass} value={listDate} max={schedule.bookableThrough} disabled={busy} onChange={(event) => setHistoryDate(event.target.value)} aria-describedby="reservation-history-date-hint" /></Field></div><div className="mt-5"><ReservationQueryState pending={mine.isPending} error={mine.error} retry={() => void mine.refetch()} />{!mine.error && mine.data && (mine.data.length ? <ul className="space-y-3">{mine.data.map((reservation) => <ReservationCard key={reservation.id} reservation={reservation} now={now} disabled={busy} onCancel={(id) => setCancelId(id)} />)}</ul> : <p className="text-sm text-navy-soft">선택한 날짜에 내 개인·소속 팀 예약이 없어요.</p>)}</div></section>}
  </div>;
}

function ReservationWorkspace() { const { member } = useSession(); const scope = directoryScope(member!); const initialDate = useSearchParams().get("date") ?? ""; return <ReservationBoard key={`${scope}:${initialDate}`} member={member!} scope={scope} initialDate={initialDate} />; }
export default function ReservationsPage() { return <AppShell><SessionGate><Suspense fallback={<p role="status" className="py-6 text-navy-soft">예약 날짜를 확인하고 있어요.</p>}><ReservationWorkspace /></Suspense></SessionGate></AppShell>; }
