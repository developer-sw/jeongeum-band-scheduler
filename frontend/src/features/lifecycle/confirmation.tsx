"use client";

import { useEffect, useId, useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { lifecycleReasonError } from "./input";
import type { LifecycleResult, LifecycleTarget } from "./types";

export function LifecycleConfirmation({ target, self, stale, pending, uncertain, error, onConfirm, onRetry, onCancel }: {
  target: LifecycleTarget; self?: boolean; stale: boolean; pending: boolean; uncertain: boolean; error: string | null;
  onConfirm: (reason: string) => void; onRetry: () => void; onCancel: () => void;
}) {
  const [reason, setReason] = useState(""); const [acknowledged, setAcknowledged] = useState(false);
  const [validation, setValidation] = useState<string | null>(null);
  const id = useId(); const dialog = useRef<HTMLDivElement>(null);
  const locked = pending || uncertain; const withdraw = target.kind === "WITHDRAW";
  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const oldOverflow = document.body.style.overflow; document.body.style.overflow = "hidden";
    dialog.current?.focus();
    return () => { document.body.style.overflow = oldOverflow; previous?.focus(); };
  }, []);
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (!acknowledged || locked || stale) return;
    const failure = lifecycleReasonError(reason); setValidation(failure);
    if (!failure) onConfirm(reason);
  }
  return <div className="fixed inset-0 z-50 flex items-center justify-center bg-navy/35 p-4" onKeyDown={(event) => {
    if (event.key === "Escape" && !locked) { event.preventDefault(); onCancel(); }
    if (event.key !== "Tab") return;
    const elements = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled),textarea:not(:disabled),[tabindex="0"]');
    if (!elements?.length) { event.preventDefault(); return; }
    const first = elements[0], last = elements[elements.length - 1];
    if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog.current)) { event.preventDefault(); last.focus(); }
    else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog.current)) { event.preventDefault(); first.focus(); }
  }}><div ref={dialog} role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-consequences`} tabIndex={-1} className="max-h-[90dvh] w-full max-w-xl overflow-y-auto rounded-3xl border border-navy/15 bg-white p-6 shadow-xl sm:p-8">
    <h2 id={`${id}-title`} className="text-xl font-bold break-words">{target.name}{withdraw ? "님의 탈퇴를 처리할까요?" : " 팀을 해체할까요?"}</h2>
    <div id={`${id}-consequences`} className="mt-4 space-y-3 text-sm leading-7 text-navy-soft">
      {withdraw ? <><p>미래·진행 중 개인 예약과 관련 교환·예정 알림을 정리해요. 이 회원이 만든 존속 팀의 예약은 유지해요.</p><p>모든 기기의 Push와 로그인 세션을 해제해요. 팀장 역할은 해제하며 후임은 전체 관리자가 직접 지정해야 해요.</p></> : <p>미래·진행 중 팀 예약과 관련 교환·예정 알림을 정리하고 반복 고정 배정을 종료해요. 다른 팀과 개인 예약은 유지해요.</p>}
      <p>완료된 이용 이력은 보존해요. 이 화면에서 탈퇴·해체를 되돌릴 수 없어요.</p>
      {self && <p className="font-bold text-navy">현재 로그인한 본인 계정이에요. 처리 후 모든 기기에서 로그아웃돼요.</p>}
    </div>
    {stale && !uncertain && <p role="alert" className="mt-4 rounded-xl bg-paper p-4 text-sm">대상 상태가 변경됐거나 최신 상태를 확인할 수 없어요. 창을 닫고 목록을 새로 확인한 뒤 다시 진행해 주세요.</p>}
    <form className="mt-5 space-y-4" onSubmit={submit} aria-busy={pending}>
      <Field id={`${id}-reason`} label="처리 사유 (선택)" hint="500자 이내. 비밀번호 등 민감한 정보는 입력하지 마세요."><textarea id={`${id}-reason`} rows={3} maxLength={500} className={inputClass} value={reason} onChange={(event) => setReason(event.target.value)} disabled={locked || stale} aria-describedby={`${id}-reason-hint`} /></Field>
      <label className="flex items-start gap-3 text-sm leading-7"><input type="checkbox" className="mt-2" checked={acknowledged} onChange={(event) => setAcknowledged(event.target.checked)} disabled={locked || stale} /><span>예약·권한·알림에 미치는 영향을 확인했어요.</span></label>
      <FormError message={validation ?? error} />
      {uncertain ? <UncertainLifecycle pending={pending} onRetry={onRetry} /> : <div className="flex flex-wrap gap-3"><button type="submit" className={primaryButton} disabled={!acknowledged || pending || stale}>{pending ? "처리하는 중…" : withdraw ? "탈퇴 처리 확정" : "팀 해체 확정"}</button><button type="button" className={secondaryButton} disabled={pending} onClick={onCancel}>돌아가기</button></div>}
    </form>
  </div></div>;
}

export function UncertainLifecycle({ pending, onRetry }: { pending: boolean; onRetry: () => void }) {
  return <div role="alert" className="space-y-3 rounded-2xl bg-paper p-4 text-sm leading-7"><p>응답을 확인하지 못했어요. 실제 처리가 완료됐을 수 있어요. 다른 탈퇴·해체 요청을 보내지 않고 처음 확인한 대상·버전·사유로 결과를 재확인해요.</p><button type="button" className={secondaryButton} disabled={pending} onClick={onRetry}>{pending ? "확인하는 중…" : "같은 요청 결과 다시 확인"}</button><p className="text-navy-soft">새로고침·창 닫기 전에 결과를 확인해 주세요. 재접속하면 최신 목록에서 상태를 확인해야 해요.</p></div>;
}

export function lifecycleResultMessage(result: LifecycleResult) {
  return `${result.status === "WITHDRAWN" ? "탈퇴를 처리했어요" : "팀을 해체했어요"}. 예약 취소 ${result.canceledReservations}건 · 교환 종료 ${result.invalidatedExchanges}건 · 반복 배정 종료 ${result.endedAssignments}건 · 기기 알림 해제 ${result.deactivatedSubscriptions}건 · 세션 해제 ${result.revokedSessions}건. 완료된 이용 이력은 보존했어요.`;
}
