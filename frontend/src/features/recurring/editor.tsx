"use client";

import { useEffect, useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import type { Team } from "@/features/directory/types";
import type { BlockCode } from "@/features/reservations/types";
import { previewAssignment } from "./api";
import { assignmentInputError, previewInputKey, revisionAt } from "./assignment-input";
import { assignmentConflicts, assignmentErrorMessage } from "./errors";
import { ConflictList, PreviewView, blockNames, weekdayNames } from "./view";
import type { Assignment, AssignmentEndInput, AssignmentIntent, AssignmentPreview, AssignmentPreviewInput, Weekday } from "./types";

export function AssignmentEditor({ assignment, teams, today, disabled, onApply, onClose }: { assignment: Assignment | null; teams: Team[]; today: string; disabled: boolean; onApply: (intent: AssignmentIntent) => void; onClose: () => void }) {
  const title = useRef<HTMLHeadingElement>(null); const controller = useRef<AbortController | null>(null);
  useEffect(() => { title.current?.focus(); return () => controller.current?.abort(); }, []);
  const revision = assignment ? revisionAt(assignment, today) : undefined;
  const [teamId, setTeamId] = useState(assignment?.teamId ?? ""); const [weekday, setWeekday] = useState<Weekday>(revision?.weekday ?? 1); const [blockCode, setBlockCode] = useState<BlockCode>(revision?.blockCode ?? "B07"); const [effectiveDate, setEffectiveDate] = useState(today);
  const [preview, setPreview] = useState<{ key: string; result: AssignmentPreview } | null>(null); const [pending, setPending] = useState(false); const [failure, setFailure] = useState<unknown>(null); const [validation, setValidation] = useState<string | null>(null);
  const input: AssignmentPreviewInput = { teamId, weekday, blockCode, effectiveDate, ...(assignment ? { assignmentId: assignment.id, expectedVersion: assignment.version } : {}) };
  const currentPreview = preview?.key === previewInputKey(input) ? preview.result : null; const busy = disabled || pending;
  function invalidate() { setPreview(null); setFailure(null); setValidation(null); }
  async function check(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (controller.current || disabled) return;
    const error = assignmentInputError(input, today, teams); if (error) { setValidation(error); return; }
    const active = new AbortController(); controller.current = active; setPending(true); setPreview(null); setFailure(null); setValidation(null);
    try { const result = await previewAssignment(input, active.signal); if (!active.signal.aborted) setPreview({ key: previewInputKey(input), result }); }
    catch (error) { if (!active.signal.aborted) setFailure(error); }
    finally { controller.current = null; if (!active.signal.aborted) setPending(false); }
  }
  function apply() {
    if (!currentPreview?.canApply || busy || assignmentInputError(input, today, teams)) return;
    onApply(assignment ? { kind: "UPDATE", assignmentId: assignment.id, input: { expectedVersion: assignment.version, weekday, blockCode, effectiveDate } } : { kind: "CREATE", input: { teamId, weekday, blockCode, effectiveDate } });
  }
  return <section className="space-y-5 rounded-3xl border-2 border-yellow p-6 sm:p-8" aria-labelledby="assignment-editor-title"><h2 id="assignment-editor-title" ref={title} tabIndex={-1} className="text-xl font-bold">{assignment ? `고정 배정 #${assignment.id} 패턴 수정` : "새 주간 고정 배정"}</h2><p className="text-sm leading-7 text-navy-soft">정규조·추가조는 합주실 전체를 독점해요. 한 배정은 매주 한 블록이며 60분 원래 고정 회차는 하루 한도에서 면제돼요.</p>{assignment && <p className="rounded-xl bg-paper p-4 text-sm leading-7">적용 시작일 이후의 시작 전·개별 예외 없는 회차를 새 패턴에 맞춰 처리해요. 개별 취소·변경·교환과 진행·과거 회차는 보존해요. 경계 주차는 기존·새 예정일이 모두 적용일 이후일 때만 바뀌어요.</p>}
    <form method="post" onSubmit={(event) => void check(event)} className="grid gap-5 sm:grid-cols-2" aria-busy={busy}><Field id="assignment-team" label="배정할 팀"><select id="assignment-team" className={inputClass} value={teamId} onChange={(event) => { setTeamId(event.target.value); invalidate(); }} disabled={busy || Boolean(assignment)} required><option value="">활성 팀 선택</option>{teams.filter((team) => team.status === "ACTIVE").map((team) => <option key={team.id} value={team.id}>{team.name} · {team.kind === "REGULAR" ? "정규조" : "추가조"}</option>)}</select></Field><Field id="assignment-weekday" label="매주 요일"><select id="assignment-weekday" className={inputClass} value={weekday} onChange={(event) => { setWeekday(Number(event.target.value) as Weekday); invalidate(); }} disabled={busy}>{Object.entries(weekdayNames).map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></Field><Field id="assignment-block" label="고정 시간 블록"><select id="assignment-block" className={inputClass} value={blockCode} onChange={(event) => { setBlockCode(event.target.value as BlockCode); invalidate(); }} disabled={busy}>{Object.entries(blockNames).map(([value, name]) => <option key={value} value={value}>{name}</option>)}</select></Field><Field id="assignment-effective-date" label="적용 시작일" hint="한국 날짜 기준 오늘부터 선택해요. 열린 15일 이후의 고정 패턴 충돌도 검사해요."><input id="assignment-effective-date" type="date" className={inputClass} value={effectiveDate} min={today} onChange={(event) => { setEffectiveDate(event.target.value); invalidate(); }} disabled={busy} required aria-describedby="assignment-effective-date-hint" /></Field><div className="flex flex-wrap gap-3 sm:col-span-2"><button type="submit" className={secondaryButton} disabled={busy || !teams.some((team) => team.status === "ACTIVE")}>{pending ? "배정안을 확인 중…" : "배정안 미리 확인"}</button><button type="button" className={secondaryButton} disabled={busy} onClick={onClose}>닫기</button></div></form><FormError message={validation ?? (failure ? assignmentErrorMessage(failure) : null)} /><ConflictList conflicts={assignmentConflicts(failure)} />{currentPreview && <><PreviewView preview={currentPreview} /><button type="button" className={primaryButton} disabled={busy || !currentPreview.canApply} onClick={apply}>{disabled ? "적용 결과 확인 중…" : assignment ? "확인한 패턴 적용" : "확인한 배정 생성"}</button></>}
  </section>;
}

export function AssignmentEndPanel({ assignment, today, disabled, onEnd, onClose }: { assignment: Assignment; today: string; disabled: boolean; onEnd: (input: AssignmentEndInput) => void; onClose: () => void }) {
  const title = useRef<HTMLHeadingElement>(null); useEffect(() => { title.current?.focus(); }, []);
  const [endDate, setEndDate] = useState(today);
  return <section className="space-y-4 rounded-3xl border border-yellow bg-yellow/10 p-6" aria-labelledby="assignment-end-title"><h2 id="assignment-end-title" ref={title} tabIndex={-1} className="text-xl font-bold">고정 배정 #{assignment.id} {assignment.status === "ENDED" ? "종료일 앞당기기" : "종료 지정"}</h2><p className="text-sm font-semibold break-words">{assignment.teamName}</p><p className="text-sm leading-7">종료일 당일까지 배정을 유지해요. 그 이후의 시작 전·개별 예외 없는 고정 예약은 취소하고 새 회차 생성을 중단해요. 개별 취소·변경·교환 예외와 진행·과거 회차는 보존해요.</p><p className="text-xs leading-6 text-navy-soft">종료를 지정한 후에도 미래 종료일까지 회차가 생성될 수 있어요. 팀 해체와는 다른 작업이에요.</p>{assignment.endDate && <p className="text-sm leading-7">현재 종료일 {assignment.endDate}. 종료일은 앞당길 수 있으며 뒤로 연장하지 않아요.</p>}<Field id="assignment-end-date" label="배정을 유지할 마지막 날짜"><input id="assignment-end-date" type="date" className={inputClass} value={endDate} min={today} max={assignment.endDate ?? undefined} required disabled={disabled} onChange={(event) => setEndDate(event.target.value)} /></Field><div className="flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={disabled || !endDate || endDate < today || Boolean(assignment.endDate && endDate > assignment.endDate)} onClick={() => onEnd({ expectedVersion: assignment.version, endDate })}>{disabled ? "종료 결과 확인 중…" : "이 날짜까지 유지하고 종료"}</button><button type="button" className={secondaryButton} disabled={disabled} onClick={onClose}>닫기</button></div></section>;
}
