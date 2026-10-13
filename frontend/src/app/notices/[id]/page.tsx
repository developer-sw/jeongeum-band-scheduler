"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { FormError, SessionGate, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { NoticeEditor } from "@/features/notices/editor";
import { noticeErrorMessage } from "@/features/notices/errors";
import { noticeChanged } from "@/features/notices/input";
import { noticeQuery, refreshNotices } from "@/features/notices/queries";
import { useNoticeAction } from "@/features/notices/use-notice-action";
import { UncertainNotice, noticeTime } from "@/features/notices/view";
import type { Notice, NoticeIntent } from "@/features/notices/types";

export function NoticeDetail({ id, scope, admin }: { id: string; scope: string; admin: boolean }) {
  const client = useQueryClient(); const query = useQuery(noticeQuery(scope, id)); const action = useNoticeAction();
  const [editor, setEditor] = useState<Notice | null>(null); const [deleting, setDeleting] = useState<Notice | null>(null); const [status, setStatus] = useState<string | null>(null); const returnFocus = useRef<HTMLElement | null>(null);
  const busy = action.pending || action.uncertain; const stale = Boolean(editor && noticeChanged(editor, query.data)); const deleteStale = Boolean(deleting && noticeChanged(deleting, query.data));
  function selected() { if (busy) return false; returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; action.clearFailure(); setStatus(null); return true; }
  function close() { setEditor(null); setDeleting(null); returnFocus.current?.focus(); }
  async function completed(result?: Notice) { if (result) { client.setQueryData(noticeQuery(scope, id).queryKey, result); close(); setStatus(result.deleted ? "공지 삭제 결과를 확인했어요. 이전 알림을 열어도 삭제된 공지 내용은 제공하지 않아요." : "공지 수정 결과를 확인했어요. 수정으로 새 알림을 보내지 않아요."); } await refreshNotices(client); }
  async function apply(intent: NoticeIntent) { setStatus(null); await completed(await action.run(intent)); }
  async function retry() { await completed(await action.retry()); }
  async function reload() { if (!busy && (stale || deleteStale || action.failure)) { close(); action.clearFailure(); } await refreshNotices(client); }
  return <div className="space-y-6"><div className="flex flex-wrap gap-3"><Link href="/notices" className={secondaryButton}>전체 공지로</Link><button type="button" className={secondaryButton} disabled={action.pending || query.isFetching} onClick={() => void reload()}>최신 상태 새로 확인</button></div>
    <FormError message={action.failure ? noticeErrorMessage(action.failure) : null} />{action.uncertain && <UncertainNotice pending={action.pending} onRetry={() => void retry()} />}{status && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">{status}</p>}
    {query.isPending && <p role="status" className="py-6 text-navy-soft">공지 내용을 확인하고 있어요.</p>}
    {query.error ? <FormError message={noticeErrorMessage(query.error)} /> : query.data && <>{query.data.deleted ? <section className={panelClass}><h1 className="text-2xl font-extrabold">삭제된 공지예요</h1><p className="mt-3 text-sm text-navy-soft">최신 전체 공지 목록에서 다른 안내를 확인해 주세요.</p></section> : <article className={panelClass}><div className="flex flex-wrap items-center gap-3">{query.data.pinned && <span className="rounded-lg bg-yellow/20 px-3 py-1 text-sm font-bold">중요 공지</span>}<p className="text-xs leading-6 text-navy-soft">게시 {noticeTime(query.data.createdAt)}{query.data.updatedAt !== query.data.createdAt && ` · 수정 ${noticeTime(query.data.updatedAt)}`}</p></div><h1 className="mt-4 text-3xl font-extrabold break-words">{query.data.title}</h1><p className="mt-6 whitespace-pre-wrap break-words text-base leading-8">{query.data.body}</p>{admin && <div className="mt-6 flex flex-wrap gap-3"><button type="button" className={secondaryButton} disabled={busy} onClick={() => { if (selected()) { setEditor(structuredClone(query.data!)); setDeleting(null); } }}>공지 수정</button><button type="button" className={secondaryButton} disabled={busy} onClick={() => { if (selected()) { setDeleting(structuredClone(query.data!)); setEditor(null); } }}>공지 삭제</button></div>}</article>}</>}
    {admin && editor && <NoticeEditor key={`${scope}:${editor.id}:${editor.version}`} notice={editor} disabled={busy || Boolean(query.error)} stale={stale} uncertain={action.uncertain} onApply={(intent) => void apply(intent)} onClose={close} />}
    {admin && deleting && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="text-xl font-bold">공지를 삭제할까요?</h2><p className="mt-3 font-semibold break-words">{deleting.title}</p><p className="mt-3 text-sm leading-7">목록과 상세에서 공지 내용을 숨겨요. 이미 받은 알림함 이력은 유지되며 해당 공지를 열면 삭제 안내를 표시해요.</p>{deleteStale && !action.uncertain && <p className="mt-3 text-sm leading-7">공지 내용이 먼저 변경됐어요. 최신 상태를 확인하고 삭제를 다시 선택해 주세요.</p>}<div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={busy || deleteStale || Boolean(query.error)} onClick={() => void apply({ kind: "DELETE", id: deleting.id, input: { expectedVersion: deleting.version } })}>{action.pending ? "처리 결과 확인 중…" : "확인하고 삭제"}</button><button type="button" className={secondaryButton} disabled={busy} onClick={close}>돌아가기</button></div></section>}
  </div>;
}
function Workspace() { const { member } = useSession(); const params = useParams<{ id: string }>(); const scope = directoryScope(member!); return <NoticeDetail key={`${scope}:${params.id}`} id={params.id} scope={scope} admin={member!.globalRole === "GLOBAL_ADMIN"} />; }
export default function NoticePage() { return <AppShell><SessionGate><Workspace /></SessionGate></AppShell>; }
