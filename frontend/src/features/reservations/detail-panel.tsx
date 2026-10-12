"use client";

import Link from "next/link";
import { useEffect, useRef } from "react";
import { useQuery } from "@tanstack/react-query";
import { FormError, primaryButton, secondaryButton } from "@/components/auth-ui";
import type { Member } from "@/features/auth/types";
import { ChangeForm } from "./change-form";
import { changeUnavailableReason } from "./change";
import { reservationErrorMessage } from "./errors";
import { reservationQuery } from "./queries";
import { blockLabel, dateLabel, phaseAt } from "./time";
import type { ChangeReservationInput, ReservationDetail, ScheduleSnapshot } from "./types";
import { cancellationNotice, instrumentNames, quotaHistoryLabel, subjectLabel } from "./view";

export type ReservationPanelMode = "DETAIL" | "CANCEL" | "CHANGE";

export function SuccessorLinks({ reservation, disabled = false }: { reservation: ReservationDetail; disabled?: boolean }) {
  if (reservation.status !== "REPLACED") return null;
  return <div className="mt-4 text-sm leading-7"><p>이 예약은 변경·교환 전 이력이에요. 후속 예약에서 현재 상태를 확인해 주세요.</p>{reservation.successorIds.length > 0 ? <ul className="mt-2 flex flex-wrap gap-3">{reservation.successorIds.map((id) => <li key={id}>{disabled ? <span className="text-navy-soft">후속 예약 #{id} · 요청 결과 확인 후 이동할 수 있어요.</span> : <Link href={`/reservations?reservationId=${encodeURIComponent(id)}`} className={secondaryButton}>후속 예약 #{id} 확인 →</Link>}</li>)}</ul> : <p>연결된 후속 예약을 불러오지 못했어요. 최신 상세를 다시 확인해 주세요.</p>}</div>;
}

export function ReservationDetailPanel({ scope, id, mode, member, schedule, scheduleUnavailable, now, disabled, onMode, onCancel, onChange, onClose }: {
  scope: string; id: string; mode: ReservationPanelMode; member: Member; schedule?: ScheduleSnapshot; scheduleUnavailable: boolean;
  now: number; disabled: boolean; onMode: (mode: ReservationPanelMode) => void; onCancel: (reservation: ReservationDetail) => void;
  onChange: (reservation: ReservationDetail, input: ChangeReservationInput) => void; onClose: () => void;
}) {
  const title = useRef<HTMLHeadingElement>(null);
  useEffect(() => { title.current?.focus(); }, [id, mode]);
  const query = useQuery(reservationQuery(scope, id));
  const detail = query.data;
  const canCancel = Boolean(detail?.canCancel && detail.status === "CONFIRMED" && phaseAt(detail.startsAt, detail.endsAt, now) !== "ENDED");
  const changeReason = detail ? changeUnavailableReason(detail, now, member) : null;
  const canChange = Boolean(detail && !changeReason);
  const blocked = disabled || query.isFetching || query.isError;
  return <section className="rounded-3xl border border-yellow bg-yellow/10 p-6" aria-labelledby="reservation-detail-title">
    <h2 ref={title} id="reservation-detail-title" tabIndex={-1} className="text-xl font-bold">{mode === "CHANGE" ? "예약 변경" : mode === "CANCEL" ? "예약 취소 확인" : "예약 상세·이력"}</h2>
    {query.isPending && <p role="status" className="py-6 text-navy-soft">예약 상세를 확인하고 있어요.</p>}
    <FormError message={query.isError ? reservationErrorMessage(query.error) : null} />
    {detail && <><p className="mt-3 text-sm leading-7 break-words">예약 #{detail.id} · {subjectLabel(detail)}<br />{dateLabel(detail.date)} · {blockLabel(detail)} · {detail.subjectType === "TEAM" ? "합주실 전체" : instrumentNames(detail.instrumentCodes)}</p><p className="mt-2 text-sm leading-7">{detail.status === "REPLACED" ? "이전 예약 이력" : detail.status === "CANCELED" ? "취소된 예약" : phaseAt(detail.startsAt, detail.endsAt, now) === "FUTURE" ? "시작 전" : phaseAt(detail.startsAt, detail.endsAt, now) === "IN_PROGRESS" ? "진행 중" : "종료"} · {quotaHistoryLabel(detail)}</p><SuccessorLinks reservation={detail} disabled={disabled} />{mode !== "CHANGE" && changeReason && <p role="status" className="mt-3 text-sm leading-7 text-navy-soft">{changeReason}</p>}
      {mode === "CHANGE" && (schedule ? <ChangeForm key={`${detail.id}:${detail.version}`} member={member} reservation={detail} schedule={schedule} now={now} disabled={blocked || scheduleUnavailable} onChange={(input) => { if (!blocked && !scheduleUnavailable) onChange(detail, input); }} /> : <p role="status" className="mt-4 text-sm leading-7">변경할 시간표를 불러온 뒤 선택할 수 있어요.</p>)}
      {mode === "CANCEL" && <><p className="mt-3 text-sm leading-7">{cancellationNotice(detail, now)}</p>{detail.originKind === "FIXED" && <p className="mt-2 text-sm leading-7">이 날짜의 회차만 취소하며 다음 주 고정 배정은 유지해요.</p>}{!canCancel && <p role="status" className="mt-3 text-sm leading-7">현재 권한이나 예약 상태에 따라 취소할 수 없어요. 최신 상세를 확인해 주세요.</p>}</>}
      {mode === "CHANGE" && scheduleUnavailable && <p role="status" className="mt-3 text-sm leading-7">최신 시간표 연결을 확인해야 변경할 수 있어요. 예약·점유를 새로 불러와 주세요.</p>}
      <div className="mt-5 flex flex-wrap gap-3">{mode === "CANCEL" && <button type="button" className={primaryButton} disabled={blocked || !canCancel} onClick={() => onCancel(detail)}>{disabled ? "요청 확인 중…" : "예약 취소 확정"}</button>}{mode !== "CHANGE" && canChange && <button type="button" className={secondaryButton} disabled={blocked} onClick={() => onMode("CHANGE")}>변경할 날짜·시간 선택</button>}{mode === "DETAIL" && canChange && !blocked && <Link className={secondaryButton} href={`/exchanges?reservationId=${encodeURIComponent(detail.id)}`}>이 예약으로 교환 요청</Link>}{mode !== "CANCEL" && canCancel && <button type="button" className={secondaryButton} disabled={blocked} onClick={() => onMode("CANCEL")}>취소 확인</button>}{mode !== "DETAIL" && <button type="button" className={secondaryButton} disabled={disabled} onClick={() => onMode("DETAIL")}>상세·이력 보기</button>}</div>
    </>}
    <div className="mt-5 flex flex-wrap gap-3"><button type="button" className={secondaryButton} disabled={disabled || query.isFetching} onClick={() => void query.refetch()}>예약 상세 새로 확인</button><button type="button" className={secondaryButton} disabled={disabled} onClick={onClose}>닫기</button></div>
  </section>;
}
