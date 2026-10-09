"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { AppShell } from "@/components/app-shell";
import { Field, FormCard, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { inspectInvitation, register } from "@/features/auth/api";
import { displayNameError, loginIdError, normalizeLoginId, passwordError } from "@/features/auth/validation";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";
import { useEphemeralState } from "@/features/auth/use-ephemeral-state";

export default function JoinPage() {
  const router = useRouter();
  const { member, loading } = useSession(); const action = useTransientAction();
  const [token, setToken] = useEphemeralState(""); const [expiresAt, setExpiresAt] = useEphemeralState<string | null>(null);
  const [loginId, setLoginId] = useState(""); const [displayName, setDisplayName] = useState(""); const [password, setPassword] = useEphemeralState("");
  const [validation, setValidation] = useState<string | null>(null); const [joined, setJoined] = useState(false);

  async function inspect(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const result = await action.run((signal) => inspectInvitation(token.trim(), signal));
    if (result) setExpiresAt(result.expiresAt);
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const issue = loginIdError(loginId) ?? displayNameError(displayName) ?? passwordError(password);
    setValidation(issue ?? null); if (issue) return;
    const result = await action.run((signal) => register({ invitationToken: token.trim(), loginId: normalizeLoginId(loginId), displayName: displayName.trim(), password }, signal));
    if (result) { setToken(""); setPassword(""); setJoined(true); router.replace("/login?joined=1"); }
  }

  return <AppShell><FormCard title={joined ? "정음에 오신 걸 환영해요" : "초대로 시작해요"} description={joined ? "가입을 마쳤어요. 본인 계정으로 로그인해 주세요." : "관리자에게 받은 개인 초대를 입력해 주세요. 초대는 한 번만 사용할 수 있어요."}>
    {loading ? <p role="status">현재 계정을 확인하고 있어요.</p> : member ? <><p className="mb-5 text-sm">가입하려면 현재 계정에서 먼저 로그아웃해 주세요.</p><Link href="/" className={primaryButton}>내 정보로 이동</Link></> : joined ? <Link href="/login" className={primaryButton}>로그인하러 가기</Link> : !expiresAt ? <form method="post" onSubmit={inspect} className="space-y-5" aria-busy={action.pending}>
      <Field id="invitation-token" label="개인 초대 코드" hint="주소에 붙이지 않고 이 입력칸에만 입력해 주세요."><input id="invitation-token" type="password" className={inputClass} autoComplete="off" required maxLength={512} spellCheck={false} aria-describedby="invitation-token-hint" value={token} onChange={(event) => setToken(event.target.value)} disabled={action.pending} /></Field>
      <FormError message={action.error} /><button type="submit" className={`${primaryButton} w-full`} disabled={action.pending}>{action.pending ? "초대 확인 중…" : "초대 확인"}</button><p className="text-sm text-navy-soft">초대를 받지 못했다면 관리자에게 문의해 주세요.</p>
    </form> : <form method="post" onSubmit={submit} className="space-y-5" aria-busy={action.pending}>
      <p role="status" className="rounded-xl bg-white px-4 py-3 text-sm">초대를 확인했어요. 계정 정보를 입력해 주세요.</p>
      <Field id="join-display-name" label="이름" hint="동아리에서 알아볼 수 있는 이름을 입력해 주세요."><input id="join-display-name" className={inputClass} autoComplete="name" required value={displayName} onChange={(event) => setDisplayName(event.target.value)} aria-describedby="join-display-name-hint" disabled={action.pending} /></Field>
      <Field id="join-login-id" label="아이디" hint="영문·숫자로 시작하는 3~32자. 영문·숫자·점·밑줄·하이픈을 사용할 수 있어요. 대문자는 소문자로 저장해요."><input id="join-login-id" className={inputClass} autoComplete="username" autoCapitalize="none" spellCheck={false} required maxLength={32} value={loginId} onChange={(event) => setLoginId(event.target.value)} aria-describedby="join-login-id-hint" disabled={action.pending} /></Field>
      <Field id="join-password" label="비밀번호" hint="12자 이상, UTF-8 기준 72바이트 이내. 한글은 글자당 더 많은 바이트를 사용해요."><input id="join-password" type="password" className={inputClass} autoComplete="new-password" required value={password} onChange={(event) => setPassword(event.target.value)} aria-describedby="join-password-hint" disabled={action.pending} /></Field>
      <FormError message={validation ?? action.error} /><button type="submit" className={`${primaryButton} w-full`} disabled={action.pending}>{action.pending ? "가입하는 중…" : "가입 완료"}</button><button type="button" className={`${secondaryButton} w-full`} disabled={action.pending} onClick={() => { setExpiresAt(null); setToken(""); setPassword(""); setValidation(null); action.clearError(); }}>다른 초대 입력하기</button>
    </form>}
  </FormCard></AppShell>;
}
