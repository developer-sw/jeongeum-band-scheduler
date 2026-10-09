"use client";

import Image from "next/image";
import Link from "next/link";
import { useSyncExternalStore } from "react";
import { useSession } from "@/features/auth/session-provider";

function subscribeOnline(callback: () => void) {
  window.addEventListener("online", callback); window.addEventListener("offline", callback);
  return () => { window.removeEventListener("online", callback); window.removeEventListener("offline", callback); };
}

export function AppShell({ children }: { children: React.ReactNode }) {
  const { member } = useSession();
  const online = useSyncExternalStore(subscribeOnline, () => navigator.onLine, () => true);
  return <div className="min-h-screen bg-white">
    <a href="#main-content" className="sr-only fixed left-4 top-4 z-10 rounded-lg bg-yellow px-4 py-3 font-semibold focus:not-sr-only">본문으로 이동</a>
    <header className="mx-auto flex max-w-6xl flex-wrap items-center justify-between gap-3 px-6 py-5 sm:px-10">
      <Link href="/" aria-label="정음 홈" className="flex items-center gap-2"><Image src="/jeongeum-logo.jpg" alt="" width={52} height={52} /><span className="text-base font-extrabold tracking-[0.16em] sm:text-lg">JEONGEUM</span></Link>
      <nav aria-label="주요 메뉴" className="flex flex-wrap items-center gap-4 text-sm font-semibold">
        {member ? <><Link href="/">내 정보</Link><Link href="/teams">팀·곡 목록</Link>{member.globalRole === "GLOBAL_ADMIN" && <><Link href="/admin/members">회원 관리</Link><Link href="/admin/invitations">초대 관리</Link></>}</> : <><Link href="/login">로그인</Link><Link href="/join" className="rounded-lg bg-yellow px-3 py-2">초대 가입</Link></>}
      </nav>
    </header>
    {!online && <p role="status" className="mx-6 mb-4 rounded-xl border border-yellow bg-yellow/20 px-5 py-3 text-sm leading-6 sm:mx-10">현재 오프라인이에요. 변경 요청은 저장하거나 자동 실행하지 않아요. 연결 후 직접 다시 시도해 주세요.</p>}
    <main id="main-content" className="mx-auto max-w-6xl px-6 pb-16 sm:px-10">{children}</main>
    <footer className="border-t border-navy/10 px-6 py-7 sm:px-10"><p className="mx-auto max-w-6xl text-xs font-medium tracking-[0.15em] text-navy-soft">JEONGEUM · SENTIMENTAL MUSIC</p></footer>
  </div>;
}
