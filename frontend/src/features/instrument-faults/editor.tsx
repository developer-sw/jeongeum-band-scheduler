"use client";

import { useEffect, useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { faultInputError, faultIntent } from "./input";
import type { FaultEditorSelection, FaultIntent } from "./types";

export function FaultEditor({ selection, disabled, stale, uncertain, onApply, onClose }: { selection: FaultEditorSelection; disabled: boolean; stale: boolean; uncertain: boolean; onApply: (intent: FaultIntent) => void; onClose: () => void }) {
  const title = useRef<HTMLHeadingElement>(null); useEffect(() => { title.current?.focus(); }, []);
  const [text, setText] = useState(""); const [confirmation, setConfirmation] = useState<FaultIntent | null>(null); const [validation, setValidation] = useState<string | null>(null);
  const reporting = selection.kind === "REPORT";
  const locked = disabled || stale;
  function check(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (locked) return;
    const error = faultInputError(selection, text); setValidation(error);
    if (!error) setConfirmation(faultIntent(selection, text));
  }
  return <section aria-labelledby="fault-editor-title" className="space-y-5 rounded-3xl border-2 border-yellow bg-white p-5 sm:p-8">
    <h2 id="fault-editor-title" ref={title} tabIndex={-1} className="text-xl font-bold">{selection.instrument.name} {reporting ? "고장 등록" : "복구 처리"}</h2>
    <p className="text-sm leading-7 text-navy-soft">{reporting ? "기존 예약·점유·한도와 대기 교환은 그대로 유지돼요. 이 악기의 개인 신규 예약과 변경·교환의 새 배치는 제한하며, 팀 합주는 경고와 함께 허용해요." : "이 악기를 정상 상태로 되돌려요. 기존 고장 이력과 예약 데이터는 유지돼요."}</p>
    {!reporting && <p className="whitespace-pre-wrap break-words rounded-xl bg-paper p-4 text-sm leading-7">등록 사유: {selection.incident.description}</p>}
    {stale && !uncertain && <p role="alert" className="rounded-xl bg-paper p-4 text-sm leading-7">악기 상태가 먼저 변경됐어요. 최신 상태 새로 확인을 누른 뒤 등록 또는 복구를 다시 선택해 주세요.</p>}
    <form method="post" onSubmit={check} aria-busy={disabled} className="space-y-4"><Field id="fault-note" label={reporting ? "고장 사유" : "복구 메모 (선택)"} hint="1000자 이내로 입력해 주세요."><textarea id="fault-note" className={`${inputClass} min-h-28 resize-y`} rows={4} maxLength={1000} value={text} required={reporting} disabled={locked} aria-describedby="fault-note-hint" onChange={(event) => { setText(event.target.value); setConfirmation(null); setValidation(null); }} /></Field><FormError message={validation} /><div className="flex flex-wrap gap-3"><button type="submit" className={secondaryButton} disabled={locked}>처리 내용 확인</button><button type="button" className={secondaryButton} disabled={disabled} onClick={onClose}>닫기</button></div></form>
    {confirmation && <section role="alert" aria-labelledby="fault-confirm-title" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h3 id="fault-confirm-title" className="font-bold">{selection.instrument.name} {reporting ? "고장을 등록할까요?" : "복구를 완료할까요?"}</h3><p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{confirmation.kind === "REPORT" ? confirmation.input.description : confirmation.input.resolutionNote || "복구 메모 없음"}</p><p className="mt-3 text-sm leading-7">기존 예약을 취소하지 않으며 등록·복구 이력을 남겨요.</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={locked} onClick={() => onApply(confirmation)}>{disabled ? "처리 결과 확인 중…" : reporting ? "확인하고 고장 등록" : "확인하고 복구 완료"}</button><button type="button" className={secondaryButton} disabled={disabled} onClick={() => setConfirmation(null)}>내용 다시 수정</button></div></section>}
  </section>;
}
