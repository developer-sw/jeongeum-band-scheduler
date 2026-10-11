"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/app-shell";
import { FormError, SessionGate, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { logout } from "@/features/auth/api";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";

export default function AccountPage() {
  const session = useSession();
  const action = useTransientAction();
  const router = useRouter();
  const member = session.member;

  async function signOut() {
    const done = await action.run(async (signal) => { await logout(signal); return true; });
    if (done) { await session.replace(null); router.replace("/login"); }
  }

  return <AppShell><SessionGate>{member && <div className="space-y-6">
    <section className={panelClass}>
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0"><p className="text-sm font-semibold text-navy-soft">내 계정</p><h1 className="mt-3 text-2xl font-extrabold break-words sm:text-3xl">{member.displayName}님, 반가워요.</h1><p className="mt-3 text-sm leading-7 text-navy-soft">오늘의 합주를 준비하고, 내 공연조와 함께해요.</p></div>
        <Image src="/guitar-mascot-v2.png" alt="" width={393} height={635} sizes="70px" className="h-auto w-14 shrink-0 sm:w-17" />
      </div>
      <dl className="my-7 grid gap-4 text-sm sm:grid-cols-2">
        <div><dt className="text-navy-soft">아이디</dt><dd className="mt-1 font-semibold break-words">{member.loginId}</dd></div>
        <div><dt className="text-navy-soft">역할</dt><dd className="mt-1 font-semibold">{member.globalRole === "GLOBAL_ADMIN" ? "전체 관리자" : "회원"}</dd></div>
      </dl>
      <div className="flex flex-wrap gap-3"><Link href="/" className={primaryButton}>오늘의 시간표</Link>{member.globalRole === "GLOBAL_ADMIN" && <Link href="/admin/invitations" className={secondaryButton}>개인 초대 관리</Link>}<button type="button" className={secondaryButton} onClick={() => void signOut()} disabled={action.pending}>{action.pending ? "로그아웃하는 중…" : "로그아웃"}</button></div>
      <p className="mt-4 text-sm leading-7 text-navy-soft">공용 기기를 사용했다면 이용 후 로그아웃해 주세요.</p>
      <div className="mt-4"><FormError message={action.error} /></div>
    </section>
    <section className={panelClass}>
      <h2 className="text-xl font-bold">내 공연조와 연습곡</h2>
      {member.teamMemberships.length ? <ul className="mt-5 grid gap-3 sm:grid-cols-2">{member.teamMemberships.map((membership) => <li key={membership.teamId}><Link href={`/teams/${encodeURIComponent(membership.teamId)}`} className="block rounded-2xl border border-diary-border bg-paper p-5"><span className="block font-semibold break-words">{membership.teamName}</span><span className="mt-2 block text-sm text-navy-soft">{membership.kind === "REGULAR" ? "정규조" : "추가조"} · {membership.role === "LEADER" ? "팀장" : "팀원"}</span><span className="mt-3 block text-sm font-semibold">공연조·곡 보기 →</span></Link></li>)}</ul> : <p className="mt-3 text-sm leading-7 text-navy-soft">아직 연결된 공연조가 없어요. 가입 전 등록된 명단이 있다면 관리자에게 계정 연결을 요청해 주세요. 개인 연습 예약은 이용할 수 있어요.</p>}
      <div className="mt-5 flex flex-wrap gap-3"><Link href="/my-schedule" className={primaryButton}>내 공연조 일정</Link><Link href="/teams" className={secondaryButton}>전체 공연조·곡 보기</Link><Link href="/reservations" className={secondaryButton}>예약·이력</Link></div>
    </section>
  </div>}</SessionGate></AppShell>;
}
