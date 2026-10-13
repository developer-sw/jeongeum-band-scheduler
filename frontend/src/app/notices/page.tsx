"use client";
import { useRef, useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { FormError, SessionGate, primaryButton, secondaryButton } from "@/components/auth-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { NoticeEditor } from "@/features/notices/editor";
import { noticeErrorMessage } from "@/features/notices/errors";
import { noticesQuery, refreshNotices } from "@/features/notices/queries";
import { useNoticeAction } from "@/features/notices/use-notice-action";
import { NoticeCard, UncertainNotice } from "@/features/notices/view";
import type { Notice, NoticeIntent } from "@/features/notices/types";

function NoticeBoard({ scope, admin }: { scope: string; admin: boolean }) {
  const client = useQueryClient(); const list = useInfiniteQuery(noticesQuery(scope)); const action = useNoticeAction();
  const [editing, setEditing] = useState(false); const [notice, setNotice] = useState<string | null>(null); const returnFocus = useRef<HTMLElement | null>(null);
  const busy = action.pending || action.uncertain;
  const all = list.data?.pages.flatMap((page) => page.notices) ?? [];
  const items = all.filter((item, index) => !item.deleted && all.findIndex((other) => other.id === item.id) === index);
  function close() { setEditing(false); returnFocus.current?.focus(); }
  async function completed(result?: Notice) { if (result) { close(); setNotice(`공지 “${result.title}”의 게시 결과를 확인했어요.`); } await refreshNotices(client); }
  async function apply(intent: NoticeIntent) { setNotice(null); await completed(await action.run(intent)); }
  async function retry() { await completed(await action.retry()); }
  return <div className="space-y-6"><section><p className="text-sm font-semibold text-navy-soft">STUDIO NOTE</p><h1 className="mt-2 text-3xl font-extrabold">전체 공지</h1><p className="mt-3 text-sm leading-7 text-navy-soft">정음의 소식과 안내를 확인해요. 중요 공지를 먼저, 같은 구분 안에서는 최근 게시 순으로 보여요.</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={secondaryButton} disabled={action.pending || list.isFetching} onClick={() => void refreshNotices(client)}>최신 공지 새로 확인</button>{admin && <button type="button" className={primaryButton} disabled={busy || Boolean(list.error)} onClick={() => { returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null; setEditing(true); setNotice(null); action.clearFailure(); }}>새 공지 작성</button>}</div></section>
    <FormError message={action.failure ? noticeErrorMessage(action.failure) : null} />{action.uncertain && <UncertainNotice pending={action.pending} onRetry={() => void retry()} />}
    {notice && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7 break-words">{notice}</p>}
    {admin && editing && <NoticeEditor disabled={busy || Boolean(list.error)} onApply={(intent) => void apply(intent)} onClose={close} />}
    {list.isPending && <p role="status" className="py-6 text-navy-soft">공지를 불러오고 있어요.</p>}
    {list.error ? <div className="space-y-3"><FormError message={noticeErrorMessage(list.error)} /><button type="button" className={secondaryButton} onClick={() => void list.refetch()}>공지 다시 불러오기</button></div> : list.data && <>{items.length ? <ul className="grid items-start gap-4 lg:grid-cols-2">{items.map((item) => <NoticeCard key={item.id} notice={item} />)}</ul> : <p role="status" className="warm-panel rounded-2xl p-6 text-sm leading-7 text-navy-soft">아직 게시된 공지가 없어요.</p>}{list.hasNextPage && <button type="button" className={secondaryButton} disabled={list.isFetching} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? "이전 공지 확인 중…" : "이전 공지 더 보기"}</button>}</>}
  </div>;
}
function Workspace() { const { member } = useSession(); const scope = directoryScope(member!); return <NoticeBoard key={scope} scope={scope} admin={member!.globalRole === "GLOBAL_ADMIN"} />; }
export default function NoticesPage() { return <AppShell><SessionGate><Workspace /></SessionGate></AppShell>; }
