import Link from "next/link";
import { secondaryButton } from "@/components/auth-ui";
import { noticeTime } from "@/features/notices/view";
import { notificationTarget } from "./target";
import type { Notification } from "./types";
export function NotificationCard({ notification, disabled, onRead }: { notification: Notification; disabled: boolean; onRead: () => void }) {
  const target = notificationTarget(notification.targetPath); const unread = notification.readAt === null;
  return <li className={`min-w-0 rounded-2xl border p-5 ${unread ? "border-yellow bg-yellow/5" : "border-line bg-white"}`}><div className="flex flex-wrap items-center gap-3"><span className="rounded-lg bg-paper px-3 py-1 text-xs font-semibold">{unread ? "안 읽음" : "읽음"}</span><p className="text-xs text-navy-soft">{noticeTime(notification.createdAt)}</p></div><h2 className="mt-3 text-lg font-bold break-words">{notification.title}</h2><p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{notification.body}</p><div className="mt-4 flex flex-wrap gap-3">{target && <Link className={secondaryButton} href={target}>현재 내용 확인</Link>}{unread && <button type="button" className={secondaryButton} disabled={disabled} onClick={onRead}>읽음으로 표시</button>}</div>{!target && <p className="mt-3 text-xs leading-6 text-navy-soft">이 알림은 바로 이동할 수 있는 주소가 없어요. 관련 메뉴에서 최신 상태를 확인해 주세요.</p>}</li>;
}
