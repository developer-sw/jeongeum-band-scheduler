"use client";

import { useEffect, useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import type { Member } from "@/features/auth/types";
import { bookingInput } from "./booking";
import { blockLabel, dateLabel, phaseAt, remainingMinutes } from "./time";
import type { CreateReservationInput, InstrumentCode, ScheduleBlock, ScheduleDay, ScheduleSnapshot } from "./types";
import { ResourceList, instrumentNames } from "./view";

export type BookingFormProps = {
  member: Member;
  schedule: ScheduleSnapshot;
  day: ScheduleDay;
  block: ScheduleBlock;
  now: number;
  disabled: boolean;
  onCreate: (input: CreateReservationInput) => void;
  compact?: boolean;
  focusOnMount?: boolean;
  disabledLabel?: string;
};

export function BookingForm({ member, schedule, day, block, now, disabled, onCreate, compact = false, focusOnMount = !compact, disabledLabel }: BookingFormProps) {
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { if (focusOnMount) title.current?.focus(); }, [focusOnMount]);
  const [subject, setSubject] = useState("PERSONAL");
  const [instrument, setInstrument] = useState("");
  const [validation, setValidation] = useState<string | null>(null);
  const [reviewing, setReviewing] = useState(false);
  const personal = subject === "PERSONAL";
  const membership = member.teamMemberships.find((item) => item.teamId === subject);
  const phase = phaseAt(block.startsAt, block.endsAt, now);
  const quota = personal ? day.personalQuota : day.teamQuotas.find((item) => item.teamId === subject);
  const allowed = day.openingStatus === "OPEN" && phase !== "ENDED"
    && (personal ? block.canReservePersonal : block.canReserveTeam)
    && (personal || Boolean(membership))
    && (block.durationMinutes === 90 || Boolean(quota && quota.remainingMinutes >= 60));

  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (disabled) return;
    const input = bookingInput(member, day, block, subject, instrument, now);
    if (typeof input === "string") { setValidation(input); setReviewing(false); return; }
    setValidation(null);
    if (compact && !reviewing) { setReviewing(true); return; }
    onCreate(input);
  }

  return <section id="booking-confirmation" className={compact ? "min-w-0 space-y-4 [&_ul]:text-sm" : "rounded-3xl border-2 border-yellow p-6 sm:p-8"} aria-labelledby="booking-title">
    <h2 ref={title} id="booking-title" tabIndex={-1} className={compact ? "text-lg font-bold" : "text-xl font-bold"}>{compact ? "빠른 예약" : "선택한 시간 예약"}</h2>
    <p className={`${compact ? "" : "mt-3"} text-sm font-semibold leading-7`}>{dateLabel(day.date)} · {blockLabel(block)} · {block.durationMinutes}분</p>
    <p className="mt-1 text-sm leading-7 text-navy-soft">{phase === "IN_PROGRESS"
      ? `이미 시작한 블록이에요. 지금부터 종료까지 약 ${remainingMinutes(block, now)}분 이용할 수 있어요.`
      : phase === "ENDED" ? "이미 종료된 블록이에요. 다른 시간을 선택해 주세요." : "정해진 블록 전체를 예약해요."}</p>
    <ResourceList block={block} instruments={schedule.instruments} />
    <form method="post" onSubmit={submit} className={`${compact ? "mt-4 space-y-4" : "mt-6 space-y-5"}`} aria-busy={disabled}>
      <Field id="reservation-subject" label="연습 주체">
        <select id="reservation-subject" className={inputClass} value={subject}
          onChange={(event) => { setSubject(event.target.value); setValidation(null); setReviewing(false); }} disabled={disabled}>
          <option value="PERSONAL">나의 개인 연습</option>
          {member.teamMemberships.map((item) => <option key={item.teamId} value={item.teamId}>{item.teamName} · {item.kind === "REGULAR" ? "정규조" : "추가조"}</option>)}
        </select>
      </Field>
      {personal ? <Field id="reservation-instrument" label="악기 한 종류" hint="같은 시간에 본인 개인 예약은 한 건만 가능해요.">
        <select id="reservation-instrument" className={inputClass}
          value={block.availableInstrumentCodes.includes(instrument as InstrumentCode) ? instrument : ""}
          onChange={(event) => { setInstrument(event.target.value); setValidation(null); setReviewing(false); }}
          disabled={disabled || !allowed} required aria-describedby="reservation-instrument-hint">
          <option value="">예약 가능한 악기 선택</option>
          {schedule.instruments.filter((item) => block.availableInstrumentCodes.includes(item.code)).map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}
        </select>
      </Field> : <p className="rounded-xl bg-paper p-4 text-sm leading-7">정규조·추가조는 합주실 전체를 독점해요. 모든 악기를 함께 점유하며 다른 예약과 겹칠 수 없어요.</p>}
      <div className="rounded-xl bg-paper p-4 text-sm leading-7">
        <p>{personal ? "나의 개인 예약" : "선택한 팀 예약"} 60분 블록 사용량: {quota?.usedMinutes ?? 0}/120분 · 남은 한도 {quota?.remainingMinutes ?? 0}분</p>
        <p className="mt-1">이번 예약의 한도 소비: {block.durationMinutes === 60 ? "60분" : "0분 (90분 블록은 별도)"}{phase === "IN_PROGRESS" && block.durationMinutes === 60 && " · 잔여 시간만 이용해도 60분 한 칸으로 계산해요."}</p>
      </div>
      {!personal && block.teamWarnings.length > 0 && <p role="status" className="rounded-xl bg-red-50 p-4 text-sm leading-7 text-red-800">고장 악기: {instrumentNames(block.teamWarnings)}. 팀 예약은 가능하지만 고장 정보를 확인해 주세요.</p>}
      {!allowed && <p role="status" className="text-sm leading-7 text-navy-soft">현재 점유·개인 중복·선택한 주체의 한도·개방 상태에 따라 예약할 수 없는 시간이에요. 최신 시간표에서 다른 시간을 선택해 주세요.</p>}
      {compact && reviewing && <div role="status" className="rounded-xl border border-yellow bg-yellow/10 p-4 text-sm leading-7">
        <p className="font-bold">예약 내용을 확인해 주세요</p>
        <p>{personal ? `나의 개인 연습 · ${instrumentNames([instrument as InstrumentCode])}` : `${membership?.teamName ?? "현재 소속 팀"} · 합주실 전체`}</p>
        <p>{dateLabel(day.date)} · {blockLabel(block)}</p>
        <p className="mt-1 text-navy-soft">확정 요청이 성공해야 예약이 완료돼요.</p>
      </div>}
      <FormError message={validation} />
      <button type="submit" className={`${primaryButton} w-full ${compact ? "" : "sm:w-auto"}`}
        disabled={disabled || !allowed || (personal && !block.availableInstrumentCodes.includes(instrument as InstrumentCode))}>
        {disabled ? disabledLabel ?? "요청 확인 중…" : compact && !reviewing ? "예약 내용 확인" : "예약 확정 요청"}
      </button>
      {compact && reviewing && <button type="button" className={`${secondaryButton} w-full`} disabled={disabled} onClick={() => setReviewing(false)}>예약 내용 다시 선택</button>}
      <p className="text-sm leading-6 text-navy-soft">최종 점유·권한·한도는 서버에서 다시 검사해요. 2시간은 60분 예약 두 건으로 각각 처리해 주세요.</p>
    </form>
  </section>;
}
