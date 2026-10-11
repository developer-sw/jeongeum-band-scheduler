"use client";

import Image from "next/image";
import Link from "next/link";
import { AppShell } from "@/components/app-shell";
import { primaryButton, secondaryButton } from "@/components/auth-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { Diary } from "@/features/diary/diary";

export default function Home() {
  const session = useSession();

  return <AppShell wide>{session.loading ? <div className="diary-loading diary-card" role="status">회원 정보를 확인하고 있어요.</div>
    : session.member ? <Diary key={directoryScope(session.member)} member={session.member} scope={directoryScope(session.member)} />
    : <section className="diary-welcome diary-card"><div><p className="diary-eyebrow">JEONGEUM STUDIO NOTE</p><h1>함께 연주할<br />다음 페이지를 열어요.</h1><p className="diary-welcome-copy">한 칸의 연습, 한 곡의 설렘.<br />정음의 합주와 개인 연습을 따뜻한 다이어리에 모아요.</p><div className="diary-welcome-actions"><Link href="/login" className={primaryButton}>로그인</Link><Link href="/join" className={secondaryButton}>초대로 가입하기</Link></div>{Boolean(session.error) && <p role="status" className="mt-5 text-sm leading-7">서버 연결을 확인하지 못했어요. 잠시 후 다시 시도해 주세요.<button type="button" className="diary-text-button" onClick={() => void session.refresh()}>연결 다시 확인</button></p>}</div><div className="diary-welcome-art"><Image src="/jeongeum-warm-logo.svg" alt="정음 JEONGEUM 원형 스탬프 로고" width={220} height={220} priority className="diary-welcome-logo" /><Image src="/guitar-mascot-v2.png" alt="" width={393} height={635} sizes="(max-width: 768px) 76px, 110px" className="diary-welcome-guitar" /></div></section>}</AppShell>;
}
