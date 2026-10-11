"use client";

import Link from "next/link";
import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { FormError, inputClass, secondaryButton } from "@/components/auth-ui";
import type { Member } from "@/features/auth/types";
import { BookingForm } from "@/features/reservations/booking-form";
import { reservationErrorMessage } from "@/features/reservations/errors";
import { dateLabel } from "@/features/reservations/time";
import type { CreateReservationInput, ScheduleBlock } from "@/features/reservations/types";
import { useBookingWorkspace } from "@/features/reservations/use-booking-workspace";
import { subjectLabel } from "@/features/reservations/view";
import { visibleDays, weekDates, weekTarget } from "./calendar";
import { CalendarPopup, CalendarSidebar, DateStrip } from "./calendar-view";
import { TeamSummary } from "./team-summary";
import { Timetable, TimetableLegend } from "./timetable";

const compactQuery = "(max-width: 1099px)";
function subscribeCompact(callback: () => void) { const media = window.matchMedia(compactQuery); media.addEventListener("change", callback); return () => media.removeEventListener("change", callback); }
const compactSnapshot = () => window.matchMedia(compactQuery).matches;
const serverCompactSnapshot = () => false;

export function Diary({ member, scope }: { member: Member; scope: string }) {
  const workspace = useBookingWorkspace(scope);
  const { query, schedule, now, action, busy } = workspace;
  const [chosenDate, setChosenDate] = useState("");
  const [selection, setSelection] = useState<{ date: string; code: string } | null>(null);
  const [highlight, setHighlight] = useState("");
  const [calendarNotice, setCalendarNotice] = useState<string | null>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const dialog = useRef<HTMLDialogElement>(null);
  const compact = useSyncExternalStore(subscribeCompact, compactSnapshot, serverCompactSnapshot);
  const today = workspace.today ?? "";
  const days = schedule ? visibleDays(schedule.days, today, schedule.bookableThrough) : [];
  const selectedDate = days.some((day) => day.date === chosenDate) ? chosenDate : days[0]?.date ?? today;
  const day = days.find((item) => item.date === selectedDate);
  const selectedBlock = selection?.date === selectedDate ? day?.blocks.find((block) => block.blockCode === selection.code) : undefined;
  const ready = Boolean(schedule && now && day && selectedBlock);
  const week = selectedDate ? weekDates(selectedDate) : [];
  const previousWeek = day ? weekTarget(selectedDate, -1, days) : undefined;
  const nextWeek = day ? weekTarget(selectedDate, 1, days) : undefined;

  // One form serves both layouts. The native modal supplies an inert background
  // and keyboard focus containment; close restores the selected block's focus.
  useEffect(() => {
    const node = dialog.current;
    if (!node) return;
    if (!ready) { if (node.open) node.close(); return; }
    if (compact) {
      if (node.open && !node.matches(":modal")) node.close();
      if (!node.open) node.showModal();
    } else {
      if (node.open && node.matches(":modal")) node.close();
      if (!node.open) node.show();
    }
    return () => { if (node.open) node.close(); };
  }, [ready, compact]);

  function restoreFocus() {
    const target = trigger.current?.getClientRects().length ? trigger.current : Array.from(document.querySelectorAll<HTMLButtonElement>("[data-diary-slot]")).find((button) => button.dataset.diarySlot === `${selection?.date}_${selection?.code}` && button.getClientRects().length);
    requestAnimationFrame(() => target?.focus());
  }
  function clearSelection() {
    if (busy) return;
    setSelection(null);
    restoreFocus();
  }
  function selectDate(date: string) {
    if (busy) return;
    setChosenDate(date); setSelection(null); setCalendarNotice(null);
  }
  function selectBlock(date: string, block: ScheduleBlock, button: HTMLButtonElement) {
    if (busy) return;
    trigger.current = button; setChosenDate(date); setSelection({ date, code: block.blockCode }); setCalendarNotice(null);
  }
  async function create(input: CreateReservationInput) {
    if (query.isError) return;
    const result = await workspace.create(input);
    if (result) { setSelection(null); restoreFocus(); }
  }
  async function retry() {
    const result = await workspace.retry();
    if (result) { setSelection(null); restoreFocus(); }
  }
  const actionFeedback = <>
    <FormError message={action.error} />
    {action.uncertain && <div className="diary-uncertain" role="status"><strong>예약 결과 확인이 필요해요</strong><p>응답을 받지 못했어요. 새로 신청하지 않고 같은 내용과 요청 번호로 결과를 확인해요.</p><button type="button" className={secondaryButton} disabled={action.pending} onClick={() => void retry()}>{action.pending ? "같은 요청 확인 중…" : "같은 요청 결과 다시 확인"}</button></div>}
  </>;

  return <div className="diary-home">
    <section className="diary-intro"><div><p className="diary-eyebrow">JEONGEUM · SENTIMENTAL MUSIC</p><h1>{member.displayName}님, 안녕하세요.</h1><p className="diary-account-info"><span>아이디: {member.loginId}</span>{" "}<span>역할: {member.globalRole === "GLOBAL_ADMIN" ? "전체 관리자" : "회원"}</span></p></div>
      <div className="diary-highlight"><label htmlFor="diary-highlight-team">내 공연조 강조</label><select id="diary-highlight-team" className={inputClass} value={highlight} onChange={(event) => setHighlight(event.target.value)} disabled={!member.teamMemberships.length}>
        <option value="">{member.teamMemberships.length ? "모든 소속 공연조" : "연결된 공연조 없음"}</option>{member.teamMemberships.map((membership) => <option key={membership.teamId} value={membership.teamId}>{membership.teamName} · {membership.kind === "REGULAR" ? "정규조" : "추가조"}</option>)}
      </select><p>강조와 일정 요약만 바뀌어요. 예약 주체는 별도로 선택해요.</p></div>
    </section>
    {workspace.notice && <p role="status" className="diary-success">{workspace.notice} <Link href="/reservations">예약·이력에서 확인 →</Link></p>}
    {calendarNotice && <p role="status" className="diary-info">{calendarNotice} <Link href="/reservations">예약·이력 보기 →</Link></p>}
    {query.isPending && <div className="diary-loading diary-card" role="status">합주실 다이어리를 펼치고 있어요…</div>}
    {query.isError && <div className="diary-query-error"><FormError message={reservationErrorMessage(query.error)} /><button type="button" className={secondaryButton} onClick={() => void workspace.refresh()}>시간표 다시 불러오기</button>{schedule && <p>마지막으로 확인한 일정이에요. 연결을 확인한 뒤 예약해 주세요.</p>}</div>}
    {schedule && now && <>
      <p className="diary-range">예약 가능 {dateLabel(today)} ~ {dateLabel(schedule.bookableThrough)} <span>서울 기준 · 20초마다 갱신</span></p>
      {!days.length ? <div className="diary-card diary-info" role="status">새 날짜의 시간표를 확인하고 있어요. 잠시 후 다시 불러와 주세요.<button className={secondaryButton} type="button" onClick={() => void workspace.refresh()}>시간표 갱신</button></div> : <div className="diary-layout">
        <CalendarSidebar selected={selectedDate} today={today} through={schedule.bookableThrough} days={days} disabled={busy} onSelect={selectDate} onUnavailable={() => setCalendarNotice("예약은 서울 기준 오늘부터 14일 뒤까지 가능해요. 과거 기록은 예약·이력에서 확인해 주세요.")} />
        <section className="diary-timetable diary-card" aria-labelledby="diary-timetable-title">
          <div className="diary-timetable-heading"><div><p className="diary-eyebrow">WEEKLY STUDIO NOTE</p><h2 id="diary-timetable-title">합주실 시간표</h2></div><div className="diary-week-controls"><button type="button" aria-label="이전 주" disabled={busy || !previousWeek} onClick={() => previousWeek && selectDate(previousWeek)}>‹</button><button type="button" disabled={busy} onClick={() => selectDate(today)}>오늘</button><button type="button" aria-label="다음 주" disabled={busy || !nextWeek} onClick={() => nextWeek && selectDate(nextWeek)}>›</button><button type="button" aria-label="시간표 새로 확인" disabled={query.isFetching} onClick={() => void workspace.refresh()}>↻</button></div></div>
          <div className="diary-week-range"><span>{dateLabel(week[0])} ~ {dateLabel(week[6])}</span><CalendarPopup selected={selectedDate} today={today} through={schedule.bookableThrough} days={days} disabled={busy} onSelect={selectDate} onUnavailable={() => setCalendarNotice("예약 가능 기간 밖이에요. 과거 기록은 예약·이력에서 확인해 주세요.")} /></div>
          <DateStrip days={days} selected={selectedDate} today={today} disabled={busy} onSelect={selectDate} /><TimetableLegend />
          <p className="diary-block-guide">12~18시 60분 · 18~24시 90분 / 점유된 시간도 눌러 상세 상태를 확인할 수 있어요.</p>
          <Timetable days={days} week={week} selectedDate={selectedDate} selectedCode={selectedBlock?.blockCode} member={member} now={now} highlight={highlight} disabled={busy} onDate={selectDate} onSelect={selectBlock} />
          <div className="diary-timetable-footer"><Link href={`/reservations?date=${selectedDate}`}>선택 날짜 예약 상세·취소·이력 →</Link><span>개인과 팀의 60분 한도는 따로 계산해요.</span></div>
        </section>
        <aside className="diary-side"><TeamSummary member={member} scope={scope} schedule={schedule} today={today} now={now} highlight={highlight} onDate={selectDate} disabled={busy} />
          <div className="diary-booking-host">
            {!selectedBlock && <section className="diary-booking-placeholder diary-card"><p className="diary-eyebrow">SAVE YOUR PRACTICE</p><h2>빠른 예약</h2><div className="diary-booking-empty"><span aria-hidden="true">＋</span><p>시간표에서 날짜와<br />연습할 시간을 골라주세요.</p></div><button type="button" className="diary-disabled-booking" disabled>시간을 먼저 선택해 주세요</button><p className="diary-small">개인은 악기 한 종류, 소속 공연조는 합주실 전체를 예약해요.</p></section>}
            <dialog ref={dialog} className="diary-booking-dialog" aria-labelledby="booking-title" onCancel={(event) => { event.preventDefault(); clearSelection(); }} onKeyDown={(event) => {
              if (!compact || event.key !== "Tab") return;
              const focusable = Array.from(event.currentTarget.querySelectorAll<HTMLElement>('a[href], button:not([disabled]), select:not([disabled]), input:not([disabled]), textarea:not([disabled]), [tabindex="0"]')).filter((element) => element.getClientRects().length);
              const first = focusable[0]; const last = focusable.at(-1);
              if (!first) { event.preventDefault(); return; }
              if (event.shiftKey && (document.activeElement === first || document.activeElement === event.currentTarget)) { event.preventDefault(); last?.focus(); }
              else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
            }}>
              <div className="diary-dialog-toolbar"><p className="diary-eyebrow">SAVE YOUR PRACTICE</p><button type="button" className="diary-dialog-close" aria-label="예약 선택 닫기" disabled={busy} onClick={clearSelection}>닫기 ×</button></div>
              {selectedBlock && day && <><BookingForm key={`${selectedDate}-${selectedBlock.blockCode}`} member={member} schedule={schedule} day={day} block={selectedBlock} now={now} disabled={busy || query.isError} disabledLabel={action.uncertain ? "예약 결과 재확인 필요" : query.isError ? "연결을 확인해 주세요" : undefined} onCreate={(input) => void create(input)} compact />{actionFeedback}
                {selectedBlock.reservations.length > 0 && <div className="diary-slot-details"><h3>이 시간의 예약</h3><ul>{selectedBlock.reservations.filter((item) => item.status === "CONFIRMED").map((reservation) => <li key={reservation.id}>{subjectLabel(reservation)}{reservation.originKind === "FIXED" && " · 고정 배정"}{reservation.quotaExempt && " · 원래 60분 한도 면제"}{reservation.subjectType === "PERSONAL" && reservation.isMine && " · 내 예약"}</li>)}</ul><Link href={`/reservations?date=${selectedDate}`}>예약 상세·취소는 예약 화면에서 →</Link></div>}
              </>}
            </dialog>
          </div>
        </aside>
      </div>}
    </>}
    {!selectedBlock && (action.error || action.uncertain) && <div className="diary-action-feedback">{actionFeedback}</div>}
    <details className="diary-rules diary-card"><summary>예약 전에 알아두기 <span>연습 시간과 한도 안내</span></summary><div><p>서울 기준 오늘부터 14일 뒤까지, 매일 12:00~24:00에 연습할 수 있어요. 고정 배정 반영 중인 날짜는 준비가 끝난 뒤 열려요.</p><p>60분 예약은 개인과 각 팀마다 하루 120분까지이며 90분은 합산하지 않아요. 진행 중인 60분은 남은 시간만 이용해도 한 칸을 사용해요. 원래 관리자 고정 60분만 면제예요.</p><p>2시간은 60분 예약 두 건을 각각 확정해 주세요. 예약 취소·이력은 예약 화면, 고정 배정은 관리자 화면에서 확인해요.</p><Link href="/reservations">예약·취소·이력 보기 →</Link></div></details>
  </div>;
}
