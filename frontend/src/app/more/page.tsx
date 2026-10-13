"use client";

import Link from "next/link";
import { AppShell } from "@/components/app-shell";
import { SessionGate } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";

const serviceLinks = [
  { href: "/", title: "시간표·빠른 예약", description: "개인 연습이나 소속 공연조의 합주를 예약해요." },
  { href: "/my-schedule", title: "내 공연조 일정", description: "현재 소속된 정규조·추가조의 확정 합주를 모아 봐요." },
  { href: "/teams", title: "공연조·연습곡", description: "공연조 구성과 각 공연조의 연습곡을 확인해요." },
  { href: "/reservations", title: "예약 조회·변경·취소·이력", description: "시작 전 예약의 날짜·시간·악기를 변경하고, 취소와 지난 이용 이력을 확인해요." },
  { href: "/exchanges", title: "예약 교환", description: "시작 전 예약의 교환을 요청하고, 받은 요청의 시간·악기를 확인해 승인·거절해요." },
  { href: "/notices", title: "전체 공지", description: "정음의 중요 소식과 전체 안내를 확인해요. 전체 관리자는 공지를 작성·관리할 수 있어요." },
  { href: "/notifications", title: "내 알림함", description: "내 계정이 받은 공지·예약·교환 알림과 읽음 상태를 확인해요." },
  { href: "/account", title: "내 계정·로그아웃", description: "계정 정보와 소속 공연조를 확인하고 로그아웃해요." },
];

const managementLinks = [
  { href: "/admin/instrument-faults", title: "악기 고장 관리", description: "악기별 고장 등록·복구와 처리 이력을 관리해요." },
  { href: "/admin/recurring-assignments", title: "반복 고정 배정", description: "매주 고정 시간과 적용 회차를 관리해요." },
  { href: "/admin/members", title: "회원·명단 관리", description: "가입 전 명단과 계정 연결, 전체 관리자 역할을 관리해요." },
  { href: "/admin/invitations", title: "개인 초대 관리", description: "회원 한 명씩 사용할 수 있는 가입 초대를 발급해요." },
];

const plannedFeatures = ["예약 시작 1시간 전 알림", "Web Push 알림", "PWA 설치", "탈퇴·팀 해체 예약 정리"];

function MenuLink({ href, title, description }: { href: string; title: string; description: string }) {
  return <li><Link href={href} className="more-link"><span><span className="block font-bold break-words">{title}</span><span className="mt-1 block text-sm leading-6 text-navy-soft">{description}</span></span><span aria-hidden="true" className="shrink-0 text-lg">→</span></Link></li>;
}

export default function MorePage() {
  const { member } = useSession();
  const leaderMemberships = member?.teamMemberships.filter((membership) => membership.role === "LEADER") ?? [];

  return <AppShell><SessionGate>{member && <div className="space-y-6">
    <div><p className="text-sm font-semibold text-navy-soft">STUDIO NOTE</p><h1 className="mt-2 text-3xl font-extrabold">더보기</h1><p className="mt-3 text-sm leading-7 text-navy-soft">예약부터 공연조 관리까지, 필요한 화면을 바로 찾아보세요.</p></div>
    <section className={panelClass} aria-labelledby="service-menu-title"><h2 id="service-menu-title" className="text-xl font-bold">정음의 공간</h2><ul className="mt-5 grid gap-3 sm:grid-cols-2">{serviceLinks.map((link) => <MenuLink key={link.href} {...link} />)}</ul></section>
    {leaderMemberships.length > 0 && <section className={panelClass} aria-labelledby="leader-menu-title"><h2 id="leader-menu-title" className="text-xl font-bold">내가 이끄는 공연조</h2><p className="mt-3 text-sm leading-7 text-navy-soft">팀장은 소속 공연조의 상세 화면에서 연습곡을 관리할 수 있어요.</p><ul className="mt-5 grid gap-3 sm:grid-cols-2">{leaderMemberships.map((membership) => <MenuLink key={membership.teamId} href={`/teams/${encodeURIComponent(membership.teamId)}`} title={membership.teamName} description={`${membership.kind === "REGULAR" ? "정규조" : "추가조"} · 공연조 상세와 연습곡 관리`} />)}</ul></section>}
    {member.globalRole === "GLOBAL_ADMIN" && <section className={panelClass} aria-labelledby="admin-menu-title"><h2 id="admin-menu-title" className="text-xl font-bold">관리자 메뉴</h2><ul className="mt-5 grid gap-3 sm:grid-cols-2">{managementLinks.map((link) => <MenuLink key={link.href} {...link} />)}</ul><p className="mt-4 text-sm leading-7 text-navy-soft">공연조 생성·구성·팀장 지정은 공연조 목록과 상세 화면에서 관리해요.</p></section>}
    <section className={panelClass} aria-labelledby="planned-menu-title"><h2 id="planned-menu-title" className="text-xl font-bold">함께 준비하는 기능</h2><p className="mt-3 text-sm leading-7 text-navy-soft">아래 기능은 준비 중이에요. 아직 신청하거나 변경할 수 없어요.</p><ul className="mt-5 grid gap-3 sm:grid-cols-2">{plannedFeatures.map((title) => <li key={title}><button type="button" disabled className="planned-feature"><span className="text-sm font-semibold">{title}</span><span className="planned-feature-status">준비 중</span></button></li>)}</ul></section>
  </div>}</SessionGate></AppShell>;
}
