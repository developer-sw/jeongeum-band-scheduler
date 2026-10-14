"use client";
import Link from "next/link";
import { useState } from "react";
import { useInfiniteQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { FormError, SessionGate, primaryButton, secondaryButton } from "@/components/auth-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { notificationErrorMessage } from "@/features/notifications/errors";
import { notificationsQuery, refreshNotifications } from "@/features/notifications/queries";
import { useReadAction } from "@/features/notifications/use-read-action";
import { NotificationCard } from "@/features/notifications/view";
import type { Notification, ReadAllResult, ReadIntent } from "@/features/notifications/types";
function Inbox({ scope }: { scope: string }) {
  const client = useQueryClient(); const [unreadOnly, setUnreadOnly] = useState(false); const list = useInfiniteQuery(notificationsQuery(scope, unreadOnly)); const action = useReadAction(); const [notice, setNotice] = useState<string | null>(null);
  const pages = list.data?.pages ?? []; const unreadCount = pages[0]?.unreadCount; const through = pages[0]?.serverTime; const busy = action.pending || action.uncertain;
  const all = pages.flatMap((page) => page.notifications); const notifications = all.filter((item, index) => (!unreadOnly || item.readAt === null) && all.findIndex((other) => other.id === item.id) === index);
  async function completed(result?: Notification | ReadAllResult) { if (result) setNotice("readCount" in result ? `${result.readCount}건의 읽음 처리를 확인했어요. 처리 결과 기준으로 ${result.unreadCount}건이 아직 안 읽은 알림이에요.` : "이 알림의 읽음 결과를 확인했어요."); await refreshNotifications(client); }
  async function apply(intent: ReadIntent) { setNotice(null); await completed(await action.run(intent)); }
  async function retry() { await completed(await action.retry()); }
  return <div className="space-y-6"><div><Link href="/notifications/settings" className={secondaryButton}>기기 알림 설정</Link></div><section><p className="text-sm font-semibold text-navy-soft">MY STUDIO NOTE</p><h1 className="mt-2 text-3xl font-extrabold">내 알림함</h1><p className="mt-3 text-sm leading-7 text-navy-soft">내 계정이 받은 알림만 보여요. Push를 설치하거나 허용하지 않아도 사용할 수 있어요. 관련 화면에서는 최신 내용과 현재 권한을 다시 확인해요.</p><div className="mt-4 flex flex-wrap items-center gap-3"><button type="button" className={secondaryButton} disabled={action.pending || list.isFetching} onClick={() => void refreshNotifications(client)}>최신 알림 새로 확인</button><button type="button" className={primaryButton} disabled={busy || !through || !unreadCount || Boolean(list.error) || list.isFetching} onClick={() => through && void apply({ kind: "ALL", through })}>확인한 시각까지 모두 읽음</button></div><p className="mt-3 text-sm leading-7 text-navy-soft">모두 읽음은 이 목록을 확인한 서버 시각까지 적용해요. 이후 새로 도착한 알림은 안 읽음으로 남아요.</p></section>
    <FormError message={action.failure ? notificationErrorMessage(action.failure) : null} />{action.uncertain && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold">읽음 결과를 아직 확인하지 못했어요</h2><p className="mt-2 text-sm leading-7">이미 반영됐을 수 있어요. 원래 알림이나 같은 기준 시각의 결과를 직접 다시 확인해 주세요. 새 알림까지 자동으로 읽음 처리하지 않아요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={action.pending} onClick={() => void retry()}>{action.pending ? "결과 확인 중…" : "같은 읽음 결과 다시 확인"}</button></section>}{notice && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">{notice}</p>}
    <div className="flex flex-wrap items-center justify-between gap-3"><label className="flex min-h-11 items-center gap-3 text-sm font-semibold"><input type="checkbox" className="h-5 w-5 accent-navy" checked={unreadOnly} onChange={(event) => setUnreadOnly(event.target.checked)} />안 읽은 알림만 보기</label>{!list.error && unreadCount !== undefined && <p className="text-sm font-semibold" aria-live="polite">안 읽은 알림 {unreadCount}건</p>}</div>
    {list.isPending && <p role="status" className="py-6 text-navy-soft">내 알림을 불러오고 있어요.</p>}{list.error ? <div className="space-y-3"><FormError message={notificationErrorMessage(list.error)} /><button type="button" className={secondaryButton} onClick={() => void list.refetch()}>알림 다시 불러오기</button></div> : list.data && <>{notifications.length ? <ul className="grid items-start gap-4 lg:grid-cols-2">{notifications.map((notification) => <NotificationCard key={notification.id} notification={notification} disabled={busy} onRead={() => void apply({ kind: "SINGLE", id: notification.id })} />)}</ul> : <p role="status" className="warm-panel rounded-2xl p-6 text-sm leading-7 text-navy-soft">{unreadOnly ? "안 읽은 알림이 없어요." : "아직 받은 알림이 없어요."}</p>}{list.hasNextPage && <button type="button" className={secondaryButton} disabled={list.isFetching} onClick={() => void list.fetchNextPage()}>{list.isFetchingNextPage ? "이전 알림 확인 중…" : "이전 알림 더 보기"}</button>}</>}
  </div>;
}
function Workspace() { const { member } = useSession(); const scope = directoryScope(member!); return <Inbox key={scope} scope={scope} />; }
export default function NotificationsPage() { return <AppShell><SessionGate><Workspace /></SessionGate></AppShell>; }
