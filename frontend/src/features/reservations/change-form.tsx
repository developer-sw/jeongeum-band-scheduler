"use client";

import { useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import type { Member } from "@/features/auth/types";
import { changeInput, changeInstrumentLimit, changeQuota, changeUnavailableReason, isUnchangedReservation } from "./change";
import { blockLabel, dateLabel, phaseAt, remainingMinutes, seoulDate, timeLabel } from "./time";
import type { ChangeReservationInput, InstrumentCode, ReservationDetail, ScheduleSnapshot } from "./types";
import { instrumentNames, subjectLabel } from "./view";

export function ChangeForm({ member, reservation, schedule, now, disabled, onChange }: {
  member: Member; reservation: ReservationDetail; schedule: ScheduleSnapshot; now: number; disabled: boolean;
  onChange: (input: ChangeReservationInput) => void;
}) {
  const [date, setDate] = useState(reservation.date);
  const [blockCode, setBlockCode] = useState<string>(reservation.blockCode);
  const [instruments, setInstruments] = useState<InstrumentCode[]>([...reservation.instrumentCodes]);
  const [review, setReview] = useState<ChangeReservationInput | null>(null);
  const [validation, setValidation] = useState<string | null>(null);
  const reviewTitle = useRef<HTMLHeadingElement>(null);
  const day = schedule.days.find((item) => item.date === date);
  const block = day?.blocks.find((item) => item.blockCode === blockCode);
  const sourceReason = changeUnavailableReason(reservation, now, member);
  const allowed = !sourceReason;
  const maxInstruments = block ? changeInstrumentLimit(reservation, block) : 1;
  const quota = day ? changeQuota(member, reservation, day) : undefined;
  const noOp = isUnchangedReservation(reservation, date, blockCode, instruments);
  const candidate = changeInput(member, reservation, schedule, date, blockCode, instruments, now);
  const currentReview = review && typeof candidate !== "string" && JSON.stringify(review) === JSON.stringify(candidate);

  function edit() { setReview(null); setValidation(null); }
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled || !allowed) return;
    const input = changeInput(member, reservation, schedule, date, blockCode, instruments, now);
    if (typeof input === "string") { setValidation(input); setReview(null); return; }
    setValidation(null);
    if (!currentReview) { setReview(input); requestAnimationFrame(() => reviewTitle.current?.focus()); return; }
    onChange(input);
  }

  return <form method="post" onSubmit={submit} className="mt-5 space-y-5" aria-busy={disabled}>
    <div className="rounded-xl bg-paper p-4 text-sm leading-7"><p className="font-bold">변경할 예약의 주체</p><p>{subjectLabel(reservation)} · {reservation.subjectType === "TEAM" ? "합주실 전체" : reservation.ownerUserId === member.id ? "본인의 개인 연습" : "다른 회원의 개인 연습"}</p><p>예약 주체와 최초 예약자는 그대로 유지해요.</p></div>
    <div className="grid gap-4 sm:grid-cols-2">
      <Field id="change-reservation-date" label="변경할 날짜"><input id="change-reservation-date" type="date" className={inputClass} value={date} min={seoulDate(now)} max={schedule.bookableThrough} disabled={disabled || !allowed} required onChange={(event) => { setDate(event.target.value); edit(); }} /></Field>
      <Field id="change-reservation-block" label="변경할 고정 시간"><select id="change-reservation-block" className={inputClass} value={blockCode} disabled={disabled || !allowed || day?.openingStatus !== "OPEN"} required onChange={(event) => { setBlockCode(event.target.value); edit(); }}><option value="">시간 블록 선택</option>{day?.blocks.map((item) => <option key={item.blockCode} value={item.blockCode} disabled={phaseAt(item.startsAt, item.endsAt, now) === "ENDED"}>{blockLabel(item)} · {item.durationMinutes}분{phaseAt(item.startsAt, item.endsAt, now) === "IN_PROGRESS" ? " · 남은 시간" : ""}</option>)}</select></Field>
    </div>
    {reservation.subjectType === "PERSONAL" && <fieldset disabled={disabled || !allowed} className="space-y-3"><legend className="text-sm font-bold">{maxInstruments === 2 ? "악기 최대 두 종류" : "악기 한 종류"}</legend><p className="text-xs leading-6 text-navy-soft">{reservation.dualAssetException ? "교환에서 생긴 두 악기 예외는 90분에서 최대 두 종류를 선택해요. 90분에서 한 종류만 선택해도 예외는 유지하며, 60분으로 옮기면 한 종류만 허용하고 예외를 종료해요." : "일반 개인 예약은 90분으로 옮겨도 한 종류를 유지해요."}</p><div className="grid gap-2 sm:grid-cols-2">{schedule.instruments.map((instrument) => {
      const occupied = block?.reservations.some((item) => item.id !== reservation.id && item.status === "CONFIRMED" && item.instrumentCodes.includes(instrument.code));
      const faulty = instrument.status === "FAULTY" || block?.faultyInstrumentCodes.includes(instrument.code);
      return <label key={instrument.code} className="flex min-h-11 items-center gap-3 rounded-xl border border-navy/15 p-3 text-sm"><input type="checkbox" checked={instruments.includes(instrument.code)} onChange={(event) => { setInstruments(event.target.checked ? maxInstruments === 1 ? [instrument.code] : [...instruments, instrument.code] : instruments.filter((code) => code !== instrument.code)); edit(); }} disabled={!instruments.includes(instrument.code) && maxInstruments === 2 && instruments.length >= 2} /><span>{instrument.name}{occupied ? " · 다른 예약 사용 중" : faulty ? " · 고장" : ""}</span></label>;
    })}</div></fieldset>}
    {day?.openingStatus !== "OPEN" && <p role="status" className="text-sm leading-7">선택한 날짜는 아직 개방되지 않았어요. 고정 시간이 반영된 날짜를 선택해 주세요.</p>}
    <div className="rounded-xl bg-paper p-4 text-sm leading-7">{quota ? <p>원래 예약을 제외한 {reservation.subjectType === "PERSONAL" ? "본인" : "이 팀"}의 선택 날짜 60분 사용량: {quota.usedMinutes}/120분 · 남은 한도 {quota.remainingMinutes}분</p> : <p>다른 회원·미소속 팀의 사용 한도와 개인 동시간 예약은 이 화면의 본인 정보로 판단하지 않아요. 서버가 해당 예약 주체의 최신 한도와 상태를 확인해요.</p>}<p>변경 후 한도 소비: {noOp ? `기존 예약 유지 (${reservation.quotaExempt ? "원래 배정 면제" : `${reservation.quotaMinutes}분`})` : block?.durationMinutes === 60 ? "60분" : "0분 (90분 블록은 별도)"}</p>{!noOp && reservation.quotaExempt && <p>원래 관리자 배정의 면제는 이동한 예약에 이어지지 않아요.</p>}{block && phaseAt(block.startsAt, block.endsAt, now) === "IN_PROGRESS" && <p>이미 시작한 목적지예요. 지금부터 {timeLabel(block.endsAt)}까지 약 {remainingMinutes(block, now)}분 이용하며, 60분 블록은 남은 시간만 이용해도 60분 한 칸을 사용해요.</p>}</div>
    {reservation.originKind === "FIXED" && <p className="text-sm leading-7">이 날짜의 고정 회차만 변경하며 다음 주 배정은 유지해요.</p>}
    {reservation.subjectType === "TEAM" && block && block.teamWarnings.length > 0 && <p role="status" className="text-sm leading-7 text-red-800">고장 안내: {instrumentNames(block.teamWarnings)}. 팀 합주는 경고를 확인한 뒤 변경할 수 있어요.</p>}
    {currentReview && block && <section className="rounded-xl border border-yellow bg-yellow/10 p-4 text-sm leading-7" aria-labelledby="change-review-title"><h3 ref={reviewTitle} id="change-review-title" tabIndex={-1} className="font-bold">현재 예약 → 변경할 예약</h3><p>현재: {dateLabel(reservation.date)} · {blockLabel(reservation)} · {reservation.subjectType === "TEAM" ? "전체 독점" : instrumentNames(reservation.instrumentCodes)}</p><p>변경: {dateLabel(date)} · {blockLabel(block)} · {reservation.subjectType === "TEAM" ? "전체 독점" : instrumentNames(instruments)}</p><p>이용 시작: {timeLabel(new Date(Math.max(now, Date.parse(block.startsAt))).toISOString())}</p><p className="mt-2">{noOp ? "날짜·블록·악기가 같아 기존 예약을 그대로 유지해요." : "성공하면 기존 예약은 이력으로 보존하고 새 예약으로 연결해요. 실패하면 기존 예약과 점유·한도를 유지해요."}</p></section>}
    <p className="text-sm leading-7 text-navy-soft">변경이 거절되면 기존 예약·점유·사용 한도는 그대로 유지해요.</p>
    <FormError message={validation ?? (typeof candidate === "string" ? candidate : null)} />
    <div className="flex flex-wrap gap-3"><button type="submit" className={primaryButton} disabled={disabled || !allowed || typeof candidate === "string"}>{disabled ? "요청 확인 중…" : currentReview ? noOp ? "기존 예약 유지 확인" : "예약 변경 확정" : "변경 내용 확인"}</button>{review && <button type="button" className={secondaryButton} disabled={disabled} onClick={edit}>선택 다시 수정</button>}</div>
  </form>;
}
