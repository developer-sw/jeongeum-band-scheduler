"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AppShell } from "@/components/app-shell";
import { Field, FormCard, FormError, inputClass, primaryButton } from "@/components/auth-ui";
import { login } from "@/features/auth/api";
import { normalizeLoginId } from "@/features/auth/validation";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";
import { useEphemeralState } from "@/features/auth/use-ephemeral-state";

function JoinedNotice() {
  const searchParams = useSearchParams();
  return searchParams.get("joined") === "1" ? <p id="joined" role="status" className="rounded-xl bg-white px-4 py-3 text-sm">가입을 마쳤어요. 본인 계정으로 로그인해 주세요.</p> : null;
}

export default function LoginPage() {
  const session = useSession(); const router = useRouter(); const action = useTransientAction();
  const [loginId, setLoginId] = useState(""); const [password, setPassword] = useEphemeralState("");

  const onSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const member = await action.run((signal) => login({ loginId: normalizeLoginId(loginId), password }, signal));
    setPassword("");
    if (member) { await session.replace(member); router.replace("/"); }
  };

  return <AppShell><FormCard title="다시 만나서 반가워요" description="정음의 개인 계정으로 로그인해 주세요.">
    {session.loading ? <p role="status">현재 계정을 확인하고 있어요.</p> : session.member ? <><p className="mb-5 text-sm">이미 {session.member.displayName} 계정으로 로그인했어요.</p><Link className={primaryButton} href="/account">내 계정으로 이동</Link></> : <form method="post" onSubmit={onSubmit} className="space-y-5" aria-busy={action.pending}>
      <Suspense fallback={null}><JoinedNotice /></Suspense>
      <Field id="login-id" label="아이디"><input id="login-id" name="username" className={inputClass} autoComplete="username" autoCapitalize="none" spellCheck={false} required maxLength={32} value={loginId} onChange={(event) => setLoginId(event.target.value)} disabled={action.pending} /></Field>
      <Field id="login-password" label="비밀번호"><input id="login-password" name="password" type="password" className={inputClass} autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.target.value)} disabled={action.pending} /></Field>
      <FormError message={action.error} /><button type="submit" className={`${primaryButton} w-full`} disabled={action.pending}>{action.pending ? "로그인하는 중…" : "로그인"}</button>
      <p className="text-sm leading-7 text-navy-soft">로그인은 마지막 접속 후 90일 동안 유지돼요. 공용 기기에서는 사용 후 로그아웃해 주세요.</p>
      <p className="text-center text-sm text-navy-soft">처음 오셨나요? <Link href="/join" className="font-semibold text-navy underline underline-offset-4">초대로 가입하기</Link></p>
    </form>}
  </FormCard></AppShell>;
}
