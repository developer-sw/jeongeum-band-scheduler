"use client";

import Image from "next/image";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { useSyncExternalStore } from "react";
import { useSession } from "@/features/auth/session-provider";

function subscribeOnline(callback: () => void) {
  window.addEventListener("online", callback); window.addEventListener("offline", callback);
  return () => { window.removeEventListener("online", callback); window.removeEventListener("offline", callback); };
}

const mainLinks = [
  { href: "/", label: "시간표·예약" },
  { href: "/my-schedule", label: "내 공연조 일정" },
  { href: "/teams", label: "공연조·곡" },
  { href: "/reservations", label: "예약·이력" },
  { href: "/exchanges", label: "예약 교환" },
];

const adminLinks = [
  { href: "/admin/instrument-faults", label: "악기 고장 관리" },
  { href: "/admin/recurring-assignments", label: "반복 고정 배정" },
  { href: "/admin/members", label: "회원·명단 관리" },
  { href: "/admin/invitations", label: "개인 초대 관리" },
];

function currentPage(path: string, href: string) {
  return href === "/" ? path === href : path === href || path.startsWith(`${href}/`);
}

function TabIcon({ kind }: { kind: "home" | "schedule" | "teams" | "more" }) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    {kind === "home" && <path d="m3 10 9-7 9 7v10a1 1 0 0 1-1 1h-5v-7H9v7H4a1 1 0 0 1-1-1Z" />}
    {kind === "schedule" && <><rect x="4" y="5" width="16" height="16" rx="2" /><path d="M8 3v4m8-4v4M4 10h16m-12 4h3m3 0h2m-8 4h3" /></>}
    {kind === "teams" && <><circle cx="9" cy="8" r="3" /><path d="M3 21v-3a6 6 0 0 1 12 0v3m2-16a3 3 0 0 1 0 6m3 10v-3a6 6 0 0 0-3-5" /></>}
    {kind === "more" && <><circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" /></>}
  </svg>;
}

export function AppShell({ children, wide = false }: { children: React.ReactNode; wide?: boolean }) {
  const { member } = useSession();
  const pathname = usePathname() ?? "";
  const online = useSyncExternalStore(subscribeOnline, () => navigator.onLine, () => true);
  const container = `app-container${wide ? " app-container-wide" : ""}`;
  const navigationLink = (href: string, label: string, extraClass = "") => <Link key={href} href={href} className={`app-nav-link ${extraClass}`} aria-current={currentPage(pathname, href) ? "page" : undefined}>{label}</Link>;

  return <div className={`app-frame${member ? " app-frame-member" : ""}`}>
    <a href="#main-content" className="sr-only fixed left-4 top-4 z-60 rounded-xl bg-yellow px-4 py-3 font-semibold focus:not-sr-only">본문으로 이동</a>
    <header className={`${container} app-header`}>
      <div className="app-header-card">
        <Link href="/" aria-label="정음 합주실 다이어리 홈" className="app-brand">
          <Image src="/jeongeum-warm-logo.svg" alt="" width={58} height={58} priority />
          <span>
            <span className="app-brand-title-row">
              <span className="app-brand-title app-brand-desktop">정음 합주실 다이어리</span>
              <span className="app-brand-title app-brand-mobile">정음 합주실 다이어리</span>
              <span className="app-brand-badge">STUDIO NOTE</span>
            </span>
            <span className="app-brand-subtitle app-brand-subtitle-desktop">따뜻한 합주실 예약 공간</span>
          </span>
        </Link>
        <span className="app-brand-subtitle app-brand-subtitle-mobile">따뜻한 합주실 예약 공간</span>
        <nav aria-label="주요 메뉴" className="desktop-navigation">
          {member ? <>
            {mainLinks.map(({ href, label }) => navigationLink(href, label))}
            <details className="app-management" onKeyDown={(event) => { if (event.key === "Escape") { event.currentTarget.open = false; event.currentTarget.querySelector("summary")?.focus(); } }}>
              <summary className="app-nav-link">공지·알림 <span aria-hidden="true" className="ml-2">⌄</span></summary>
              <div className="app-management-menu">{navigationLink("/notices", "전체 공지")}{navigationLink("/notifications", "내 알림함")}{navigationLink("/notifications/settings", "알림 설정")}</div>
            </details>
            {member.globalRole === "GLOBAL_ADMIN" && <details className="app-management" onKeyDown={(event) => { if (event.key === "Escape") { event.currentTarget.open = false; event.currentTarget.querySelector("summary")?.focus(); } }}>
              <summary className="app-nav-link">관리 <span aria-hidden="true" className="ml-2">⌄</span></summary>
              <div className="app-management-menu">{adminLinks.map(({ href, label }) => navigationLink(href, label))}</div>
            </details>}
            {navigationLink("/more", "더보기")}
            {navigationLink("/account", "내 계정", "app-account-link")}
          </> : <>{navigationLink("/login", "로그인")}{navigationLink("/join", "초대 가입", "app-account-link")}</>}
        </nav>
        <nav aria-label="빠른 메뉴" className="app-compact-actions">
          {member ? <>{navigationLink("/account", "계정")}{navigationLink("/more", "더보기", "app-account-link")}</> : <>{navigationLink("/login", "로그인")}{navigationLink("/join", "가입", "app-account-link")}</>}
        </nav>
      </div>
    </header>
    {!online && <div className={container}><p role="status" className="app-offline">현재 오프라인이에요. 변경 요청은 저장하거나 자동 실행하지 않아요. 연결 후 직접 다시 시도해 주세요.</p></div>}
    <main id="main-content" className={`${container} app-main`}>{children}</main>
    <footer className="app-footer"><p className={container}>JEONGEUM · SENTIMENTAL MUSIC</p></footer>
    {member && <nav aria-label="모바일 주요 메뉴" className="mobile-navigation">
      {([
        { href: "/", label: "홈·예약", kind: "home" },
        { href: "/my-schedule", label: "내 공연조", kind: "schedule" },
        { href: "/teams", label: "공연조·곡", kind: "teams" },
        { href: "/more", label: "더보기", kind: "more" },
      ] as const).map(({ href, label, kind }) => <Link key={href} href={href} className="mobile-nav-link" aria-current={currentPage(pathname, href) ? "page" : undefined}><TabIcon kind={kind} /><span>{label}</span></Link>)}
    </nav>}
  </div>;
}
