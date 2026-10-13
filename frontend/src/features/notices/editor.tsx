"use client";
import { useEffect, useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { noticeInputError, noticeIntent } from "./input";
import type { Notice, NoticeIntent } from "./types";
export function NoticeEditor({ notice, disabled, stale = false, uncertain = false, onApply, onClose }: { notice?: Notice; disabled: boolean; stale?: boolean; uncertain?: boolean; onApply: (intent: NoticeIntent) => void; onClose: () => void }) {
  const heading = useRef<HTMLHeadingElement>(null); useEffect(() => { heading.current?.focus(); }, []);
  const [title, setTitle] = useState(notice?.title ?? ""); const [body, setBody] = useState(notice?.body ?? ""); const [pinned, setPinned] = useState(notice?.pinned ?? false);
  const [confirmation, setConfirmation] = useState<NoticeIntent | null>(null); const [validation, setValidation] = useState<string | null>(null); const locked = disabled || stale;
  function check(event: React.FormEvent<HTMLFormElement>) { event.preventDefault(); if (locked) return; const input = { title, body, pinned }; const error = noticeInputError(input); setValidation(error); if (!error) setConfirmation(noticeIntent(input, notice)); }
  const changed = () => { setConfirmation(null); setValidation(null); };
  return <section aria-labelledby="notice-editor-title" className="space-y-5 rounded-3xl border-2 border-yellow bg-white p-5 sm:p-8">
    <h2 id="notice-editor-title" ref={heading} tabIndex={-1} className="text-xl font-bold">{notice ? "공지 수정" : "새 공지 작성"}</h2>
    <p className="text-sm leading-7 text-navy-soft">중요 공지는 목록 상단에 표시해요. 중요 공지로 처음 작성할 때만 전체 활성 회원의 알림함에 알려요. 수정·고정 해제·다시 고정은 새 알림을 만들지 않아요.</p>
    {stale && !uncertain && <p role="alert" className="rounded-xl bg-paper p-4 text-sm leading-7">공지 내용이 먼저 변경됐어요. 최신 상태를 확인한 뒤 수정을 다시 선택해 주세요.</p>}
    <form method="post" onSubmit={check} aria-busy={disabled} className="space-y-4">
      <Field id="notice-title" label="제목" hint="120자 이내로 입력해 주세요."><input id="notice-title" className={inputClass} maxLength={120} required disabled={locked} aria-describedby="notice-title-hint" value={title} onChange={(event) => { setTitle(event.target.value); changed(); }} /></Field>
      <Field id="notice-body" label="본문" hint="10000자 이내의 일반 글로 작성해 주세요. HTML은 글자로 표시돼요."><textarea id="notice-body" className={`${inputClass} min-h-48 resize-y`} rows={8} maxLength={10000} required disabled={locked} aria-describedby="notice-body-hint" value={body} onChange={(event) => { setBody(event.target.value); changed(); }} /></Field>
      <label className="flex min-h-11 items-center gap-3 text-sm font-semibold"><input type="checkbox" checked={pinned} disabled={locked} onChange={(event) => { setPinned(event.target.checked); changed(); }} className="h-5 w-5 accent-navy" />중요 공지로 상단 고정</label>
      <FormError message={validation} /><div className="flex flex-wrap gap-3"><button type="submit" className={secondaryButton} disabled={locked}>게시 내용 확인</button><button type="button" className={secondaryButton} disabled={disabled} onClick={onClose}>닫기</button></div>
    </form>
    {confirmation && confirmation.kind !== "DELETE" && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h3 className="font-bold">{notice ? "이 내용으로 공지를 수정할까요?" : "새 공지를 게시할까요?"}</h3><p className="mt-3 font-semibold break-words">{confirmation.input.title}</p><p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{confirmation.input.body}</p><p className="mt-3 text-sm leading-7">{confirmation.input.pinned ? "중요 공지 · 목록 상단에 고정" : "일반 공지"} · {confirmation.kind === "CREATE" && confirmation.input.pinned ? "전체 활성 회원에게 알림함 알림을 만들어요." : "새 알림은 만들지 않아요."}</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={locked} onClick={() => onApply(confirmation)}>{disabled ? "처리 결과 확인 중…" : notice ? "확인하고 수정" : "확인하고 게시"}</button><button type="button" className={secondaryButton} disabled={disabled} onClick={() => setConfirmation(null)}>내용 다시 수정</button></div></section>}
  </section>;
}
