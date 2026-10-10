"use client";

import Link from "next/link";
import { useId } from "react";
import { secondaryButton } from "@/components/auth-ui";
import { teamKindLabel } from "@/components/directory-ui";
import { dateLabel } from "@/features/reservations/time";
import type { BlockCode } from "@/features/reservations/types";
import { nextRevisionAfter } from "./assignment-input";
import type { Assignment, AssignmentConflict, AssignmentPreview, OccurrenceState, Weekday } from "./types";

export const weekdayNames: Record<Weekday, string> = { 1: "월요일", 2: "화요일", 3: "수요일", 4: "목요일", 5: "금요일", 6: "토요일", 7: "일요일" };
export const blockNames: Record<BlockCode, string> = { B01: "12:00~13:00 · 60분", B02: "13:00~14:00 · 60분", B03: "14:00~15:00 · 60분", B04: "15:00~16:00 · 60분", B05: "16:00~17:00 · 60분", B06: "17:00~18:00 · 60분", B07: "18:00~19:30 · 90분", B08: "19:30~21:00 · 90분", B09: "21:00~22:30 · 90분", B10: "22:30~24:00 · 90분" };
export const occurrenceNames: Record<OccurrenceState, string> = { PLANNED: "생성 예정", MATERIALIZED: "고정 예약 생성됨", CANCELED_EXCEPTION: "개별 취소 · 예외 유지", CHANGED_EXCEPTION: "개별 변경 · 예외 유지", EXCHANGED_EXCEPTION: "개별 교환 · 예외 유지", ENDED: "회차 종료" };

export function ConflictList({ conflicts }: { conflicts: AssignmentConflict[] }) {
  const titleId = useId();
  if (!conflicts.length) return null;
  return <section className="rounded-2xl border border-red-200 bg-red-50 p-5" aria-labelledby={titleId}><h3 id={titleId} className="font-bold text-red-800">배정 충돌 목록</h3><ul className="mt-3 space-y-3 text-sm leading-7">{conflicts.map((conflict, index) => <li key={`${conflict.type}:${conflict.assignmentId}:${conflict.reservationId}:${index}`} className="break-words"><p className="font-semibold">{conflict.type === "PATTERN" ? `고정 배정 #${conflict.assignmentId ?? "확인 필요"}` : `기존 예약 #${conflict.reservationId ?? "확인 필요"}`}{conflict.teamName && ` · ${conflict.teamName}`}</p><p>{conflict.date ? dateLabel(conflict.date) : `매주 ${weekdayNames[conflict.weekday]}`} · {blockNames[conflict.blockCode]}</p></li>)}</ul><p className="mt-3 text-xs leading-6 text-red-800">기존 예약을 유지해요. 필요한 예약을 직접 취소하거나 배정안을 바꾼 뒤 다시 미리 확인해 주세요.</p></section>;
}

export function PreviewView({ preview }: { preview: AssignmentPreview }) {
  return <section className="space-y-4 rounded-2xl border border-yellow bg-yellow/10 p-5" aria-labelledby="assignment-preview-title"><h3 id="assignment-preview-title" className="font-bold">{preview.canApply ? "미리 확인한 배정안" : "이 배정안은 적용할 수 없어요"}</h3><p className="text-sm leading-7">미리 확인은 시간을 확보하지 않아요. 실제 적용 시 최신 점유·권한·고정 패턴을 다시 검사하며 실패하면 기존 예약과 배정을 유지해요.</p><ConflictList conflicts={preview.conflicts} />{preview.plannedOccurrences.length ? <ul className="space-y-3">{preview.plannedOccurrences.map((occurrence) => <li key={occurrence.weekStartDate} className="rounded-xl bg-white p-4 text-sm leading-7"><p className="font-semibold">{dateLabel(occurrence.date)} · {blockNames[occurrence.blockCode]}</p><p>{occurrenceNames[occurrence.state]}{occurrence.preserved && " · 기존 회차 보존"}</p></li>)}</ul> : <p className="text-sm leading-7">현재 열린 날짜에 생성할 회차가 없어요. 적용 날짜가 예약 범위에 들어오면 고정 회차를 먼저 반영해요.</p>}</section>;
}

export function AssignmentCard({ assignment, today, disabled, onEdit, onEnd }: { assignment: Assignment; today: string; disabled: boolean; onEdit: (assignment: Assignment) => void; onEnd: (assignment: Assignment) => void }) {
  const revisions = [...assignment.revisions].sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate) || a.revisionNo - b.revisionNo);
  const current = [...revisions].reverse().find((revision) => revision.effectiveDate <= today);
  const next = nextRevisionAfter(assignment, today);
  const scheduledToEnd = Boolean(assignment.endDate && assignment.endDate >= today);
  return <li className="rounded-2xl border border-navy/15 p-5"><div className="flex flex-wrap items-start justify-between gap-3"><div className="min-w-0"><h3 className="font-bold break-words">{assignment.teamName}</h3><p className="mt-1 text-xs leading-6 text-navy-soft">{teamKindLabel(assignment.teamKind)} · 고정 배정 #{assignment.id} · 버전 {assignment.version}</p></div><span className="rounded-full bg-paper px-3 py-1 text-xs">{assignment.status === "ACTIVE" ? "반복 중" : scheduledToEnd ? "종료일 지정" : "종료됨"}</span></div><p className="mt-4 text-sm leading-7">{current ? `현재 패턴: 매주 ${weekdayNames[current.weekday]} · ${blockNames[current.blockCode]}` : next ? "적용 시작 전이에요." : "현재 적용 중인 패턴이 없어요."}{next && <span className="mt-1 block">다음 패턴: {dateLabel(next.effectiveDate)}부터 매주 {weekdayNames[next.weekday]} · {blockNames[next.blockCode]}</span>}</p>{assignment.endDate && <p className="mt-2 text-sm leading-7">{dateLabel(assignment.endDate)}까지 고정 배정을 유지하고 이후 새 회차를 만들지 않아요.</p>}
    <details className="mt-4 rounded-xl bg-paper p-4"><summary className="cursor-pointer text-sm font-semibold">패턴 변경 이력 · {revisions.length}개</summary><ul className="mt-3 space-y-2 text-sm leading-7">{revisions.map((revision) => <li key={revision.id}>{dateLabel(revision.effectiveDate)}부터 · 매주 {weekdayNames[revision.weekday]} · {blockNames[revision.blockCode]} <span className="text-xs text-navy-soft">(패턴 {revision.revisionNo})</span></li>)}</ul></details>
    <details className="mt-3 rounded-xl bg-paper p-4"><summary className="cursor-pointer text-sm font-semibold">열린 기간의 회차·개별 예외 · {assignment.occurrences.length}개</summary>{assignment.occurrences.length ? <ul className="mt-3 space-y-3">{assignment.occurrences.map((occurrence) => <li key={occurrence.id} className="text-sm leading-7"><p>{dateLabel(occurrence.originalScheduledDate)} · {blockNames[occurrence.originalBlockCode]}</p><p className="text-xs text-navy-soft">{occurrenceNames[occurrence.state]} · 주차 시작 {occurrence.weekStartDate}{occurrence.reservationIds.length > 0 && ` · 연결 예약 ${occurrence.reservationIds.map((id) => `#${id}`).join(", ")}`}</p></li>)}</ul> : <p className="mt-3 text-sm leading-7">이 조회 기간에 생성한 회차가 없어요.</p>}<p className="mt-3 text-xs leading-6 text-navy-soft">개별 취소·변경·교환은 해당 주차의 예외로 유지하며 자동 생성으로 되돌리지 않아요.</p></details>
    <div className="mt-5 flex flex-wrap gap-3">{assignment.status === "ACTIVE" && <><button type="button" className={secondaryButton} disabled={disabled} onClick={() => onEdit(assignment)} aria-label={`고정 배정 #${assignment.id} 패턴 수정`}>패턴 수정</button><button type="button" className={secondaryButton} disabled={disabled} onClick={() => onEnd(assignment)} aria-label={`고정 배정 #${assignment.id} 종료 지정`}>종료 지정</button></>}{assignment.status === "ENDED" && assignment.endDate && assignment.endDate > today && <button type="button" className={secondaryButton} disabled={disabled} onClick={() => onEnd(assignment)} aria-label={`고정 배정 #${assignment.id} 종료일 앞당기기`}>종료일 앞당기기</button>}<Link href="/reservations" className={secondaryButton}>시간표·회차 취소</Link></div></li>;
}
