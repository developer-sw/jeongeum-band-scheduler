"use client";

import Image from "next/image";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AppShell } from "@/components/app-shell";
import { FormError, primaryButton, secondaryButton } from "@/components/auth-ui";
import { logout } from "@/features/auth/api";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";

export default function Home() {
  const session = useSession(); const action = useTransientAction(); const router = useRouter();
  async function signOut() {
    const done = await action.run(async (signal) => { await logout(signal); return true; });
    if (done) { await session.replace(null); router.replace("/login"); }
  }

  return <AppShell>{session.loading ? <p role="status" className="py-12 text-center text-navy-soft">회원 정보를 확인하고 있어요.</p> : session.member ? <div className="space-y-6">
    <section className="rounded-3xl bg-paper p-7 sm:p-10"><p className="text-sm font-semibold text-navy-soft">내 계정</p><h1 className="mt-3 text-3xl font-extrabold">{session.member.displayName}님, 반가워요.</h1><dl className="my-7 grid gap-4 text-sm sm:grid-cols-2"><div><dt className="text-navy-soft">아이디</dt><dd className="mt-1 font-semibold">{session.member.loginId}</dd></div><div><dt className="text-navy-soft">역할</dt><dd className="mt-1 font-semibold">{session.member.globalRole === "GLOBAL_ADMIN" ? "전체 관리자" : "회원"}</dd></div></dl><div className="flex flex-wrap gap-3">{session.member.globalRole === "GLOBAL_ADMIN" && <Link href="/admin/invitations" className={primaryButton}>개인 초대 관리</Link>}<button type="button" className={secondaryButton} onClick={() => void signOut()} disabled={action.pending}>{action.pending ? "로그아웃하는 중…" : "로그아웃"}</button></div><div className="mt-4"><FormError message={action.error} /></div></section>
    <section className="rounded-3xl border border-navy/15 p-7 sm:p-10"><h2 className="text-lg font-bold">다음 연습을 준비하고 있어요</h2><p className="mt-3 text-sm leading-7 text-navy-soft">팀 구성, 합주실 예약과 팀별 곡 목록이 준비되면 여기에서 이어갈 수 있어요.</p></section>
  </div> : <section className="grid items-center gap-8 rounded-3xl bg-paper p-7 sm:p-12 lg:grid-cols-[1.25fr_1fr] lg:p-16">
    <div><p className="mb-7 text-sm font-semibold text-navy-soft">정음의 연습, 더 가깝게</p><h1 className="text-4xl leading-tight font-extrabold tracking-tight sm:text-5xl lg:text-6xl">우리의 합주가<br />시작되는 곳.</h1><div aria-hidden="true" className="mt-7 h-1.5 w-16 rounded-full bg-yellow" /><p className="mt-7 max-w-md text-base leading-8 text-navy-soft">함께 연주하고, 각자의 소리를 더하고.<br />개인 초대로 정음의 공간을 시작하세요.</p><div className="mt-8 flex flex-wrap gap-3"><Link href="/login" className={primaryButton}>로그인</Link><Link href="/join" className={secondaryButton}>초대로 가입하기</Link></div>{Boolean(session.error) && <p role="status" className="mt-5 text-sm leading-6 text-navy-soft">서버 연결을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.</p>}</div>
    <div className="mx-auto w-full max-w-72 rounded-3xl bg-white p-3 sm:max-w-80 lg:max-w-96"><Image src="/jeongeum-logo.jpg" alt="남색과 노란색의 정음 로고" width={1254} height={1254} priority sizes="(max-width: 640px) 288px, (max-width: 1024px) 320px, 384px" className="h-auto w-full rounded-2xl" /></div>
  </section>}</AppShell>;
}
