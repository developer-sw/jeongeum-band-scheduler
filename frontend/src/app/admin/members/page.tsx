"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { Confirmation, QueryState, panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";
import { createRosterEntry, linkAccount, updateGlobalRole, updateRosterEntry } from "@/features/directory/api";
import { membersQuery, refreshDirectory, rosterQuery } from "@/features/directory/queries";
import type { AdminMember, RosterEntry } from "@/features/directory/types";
import { nameError } from "@/features/directory/validation";
import { LifecycleConfirmation, lifecycleResultMessage } from "@/features/lifecycle/confirmation";
import { lifecycleErrorMessage } from "@/features/lifecycle/errors";
import { lifecycleIntent, lifecycleTargetChanged, withdrawalBlock } from "@/features/lifecycle/input";
import { refreshLifecycle } from "@/features/lifecycle/queries";
import { finishSelfWithdrawal } from "@/features/lifecycle/self-withdrawal";
import { useLifecycleAction } from "@/features/lifecycle/use-lifecycle-action";

function RosterCard({ entry, accounts, pending, onRename, onLink }: { entry: RosterEntry; accounts: AdminMember[]; pending: boolean; onRename: (name: string) => void; onLink: (account: AdminMember) => void }) {
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(entry.name);
  const [accountId, setAccountId] = useState("");
  return <li className="rounded-2xl border border-navy/15 p-5">
    <div className="flex flex-wrap items-start justify-between gap-3"><div><h3 className="font-bold break-words">{entry.name}</h3><p className="mt-1 text-xs text-navy-soft">명단 번호 #{entry.id}</p></div><span className="rounded-full bg-paper px-3 py-1 text-xs">{entry.userId ? "가입 계정 연결됨" : "가입 전 명단"}</span></div>
    {entry.userId && <p className="mt-3 text-sm break-words">{entry.displayName} · {entry.loginId} <span className="text-navy-soft">(계정 #{entry.userId})</span></p>}
    <div className="mt-4"><button type="button" className={secondaryButton} disabled={pending} onClick={() => setEditing(!editing)}>{editing ? "이름 수정 닫기" : "명단 이름 수정"}</button></div>
    {editing && <form className="mt-4 space-y-3" onSubmit={(event) => { event.preventDefault(); onRename(name); }}><Field id={`roster-name-${entry.id}`} label="명단 이름"><input id={`roster-name-${entry.id}`} className={inputClass} value={name} onChange={(event) => setName(event.target.value)} required disabled={pending} /></Field><button type="submit" className={primaryButton} disabled={pending}>이름 저장</button></form>}
    {!entry.userId && <div className="mt-5 space-y-3"><Field id={`link-account-${entry.id}`} label="이미 가입한 계정 연결" hint="이름으로 자동 연결하지 않아요. 아이디와 계정 번호를 본인에게 확인해 주세요."><select id={`link-account-${entry.id}`} className={inputClass} value={accountId} onChange={(event) => setAccountId(event.target.value)} disabled={pending || !accounts.length} aria-describedby={`link-account-${entry.id}-hint`}><option value="">계정 선택</option>{accounts.map((account) => <option key={account.id} value={account.id}>{account.displayName} · {account.loginId} (#{account.id})</option>)}</select></Field><button type="button" className={secondaryButton} disabled={pending || !accountId} onClick={() => { const account = accounts.find((item) => item.id === accountId); if (account) onLink(account); }}>연결 내용 확인</button></div>}
  </li>;
}

function MemberManager() {
  const { member, replace } = useSession();
  const client = useQueryClient(); const action = useTransientAction(); const lifecycle = useLifecycleAction();
  const roster = useQuery(rosterQuery(member!.id)); const accounts = useQuery(membersQuery(member!.id));
  const [name, setName] = useState(""); const [validation, setValidation] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [linkTarget, setLinkTarget] = useState<{ entry: RosterEntry; account: AdminMember } | null>(null);
  const [roleTarget, setRoleTarget] = useState<AdminMember | null>(null);
  const [withdrawTarget, setWithdrawTarget] = useState<AdminMember | null>(null);
  const busy = action.pending || withdrawTarget !== null;
  const unlinkedAccounts = (accounts.data ?? []).filter((account) => !account.rosterEntryId && account.status === "ACTIVE");

  const target = withdrawTarget ? { kind: "WITHDRAW" as const, id: withdrawTarget.id, name: `${withdrawTarget.displayName} · ${withdrawTarget.loginId}`, version: withdrawTarget.version } : null;
  const currentTarget = accounts.data?.find((account) => account.id === withdrawTarget?.id);
  const targetAllowed = !accounts.error && Boolean(currentTarget && !withdrawalBlock(currentTarget, accounts.data ?? []));
  async function withdraw(reason = "", retry = false) {
    if (!target) return;
    const result = retry ? await lifecycle.retry() : await lifecycle.run(lifecycleIntent(target, reason));
    if (result) {
      setWithdrawTarget(null);
      if (target.id === member!.id) {
        void finishSelfWithdrawal(() => replace(null), () => window.location.replace("/login"));
        return;
      }
      setNotice(lifecycleResultMessage(result));
    }
    await refreshLifecycle(client);
  }

  async function change(task: (signal: AbortSignal) => Promise<unknown>, message: string) {
    setValidation(null); setNotice(null);
    const done = await action.run(async (signal) => { await task(signal); return true; });
    if (done) { setNotice(message); await refreshDirectory(client); }
    return done;
  }
  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const error = nameError(name, 40, "이름");
    if (error) { setValidation(error); return; }
    if (await change((signal) => createRosterEntry(name.trim(), signal), "가입 전 명단을 등록했어요. 팀 배정과 개인 초대 발급을 이어서 처리할 수 있어요.")) setName("");
  }
  async function rename(entry: RosterEntry, value: string) {
    const error = nameError(value, 40, "이름"); if (error) { setValidation(error); return; }
    await change((signal) => updateRosterEntry(entry, value.trim(), signal), "명단 이름을 수정했어요. 계정 연결과 팀 소속은 유지돼요.");
  }
  async function confirmLink() {
    if (!linkTarget) return;
    const { entry, account } = linkTarget;
    if (await change((signal) => linkAccount(entry, account.id, signal), "명단과 계정을 연결했어요. 미리 배정한 팀과 팀장 역할을 해당 계정에서 사용할 수 있어요.")) setLinkTarget(null);
  }
  async function confirmRole() {
    if (!roleTarget) return;
    const role = roleTarget.globalRole === "GLOBAL_ADMIN" ? "MEMBER" : "GLOBAL_ADMIN";
    if (await change((signal) => updateGlobalRole(roleTarget, role, signal), "전체 관리자 권한을 변경했어요. 팀장 역할은 별도로 유지돼요.")) setRoleTarget(null);
  }

  return <div className="space-y-7">
    <section><p className="text-sm font-semibold text-navy-soft">전체 관리자</p><h1 className="mt-2 text-3xl font-extrabold">회원·가입 전 명단 관리</h1><p className="mt-3 text-sm leading-7 text-navy-soft">먼저 명단을 등록하고 팀에 배정하세요. 명단 번호에 연결된 개인 초대로 가입하면 같은 사람이 여러 팀에 속해도 하나의 계정을 사용해요.</p><div className="mt-4 flex flex-wrap gap-3"><Link href="/admin/invitations" className={secondaryButton}>명단별 초대 발급</Link><Link href="/teams" className={secondaryButton}>팀 구성 관리</Link><button type="button" className={secondaryButton} disabled={busy} onClick={() => void refreshDirectory(client)}>목록 새로 불러오기</button></div></section>
    <form onSubmit={(event) => void create(event)} method="post" className="space-y-4 rounded-3xl bg-paper p-6 sm:p-8" aria-busy={busy}><h2 className="text-xl font-bold">가입 전 명단 등록</h2><Field id="new-roster-name" label="이름" hint="동명이인도 각각 등록할 수 있어요. 연결과 팀 배정은 명단 번호로 구분해요."><input id="new-roster-name" className={inputClass} value={name} onChange={(event) => setName(event.target.value)} required disabled={busy} autoComplete="off" aria-describedby="new-roster-name-hint" /></Field><button type="submit" className={primaryButton} disabled={busy}>{busy ? "처리하는 중…" : "명단 등록"}</button></form>
    <FormError message={validation ?? action.error} />{notice && <p role="status" className="rounded-xl bg-paper p-4 text-sm leading-7">{notice}</p>}
    {linkTarget && <Confirmation title={`${linkTarget.entry.name} 명단 #${linkTarget.entry.id}에 이 계정을 연결할까요?`} description={`${linkTarget.account.displayName} · ${linkTarget.account.loginId} (계정 #${linkTarget.account.id}). 미리 배정한 팀·팀장 역할이 이 계정에 연결되며 해당 명단의 미사용 초대는 해지돼요. 본인 계정인지 확인해 주세요.`} pending={busy} onConfirm={() => void confirmLink()} onCancel={() => setLinkTarget(null)} confirmLabel="계정 연결" />}
    {roleTarget && <Confirmation title={`${roleTarget.displayName}님의 전체 관리자 권한을 ${roleTarget.globalRole === "GLOBAL_ADMIN" ? "해제" : "부여"}할까요?`} description={`${roleTarget.loginId} (계정 #${roleTarget.id}, 현재 버전 ${roleTarget.version}). 전체 관리자는 모든 회원·팀 구성과 초대를 관리할 수 있어요. 마지막 활성 전체 관리자의 권한은 해제할 수 없어요.`} pending={busy} onConfirm={() => void confirmRole()} onCancel={() => setRoleTarget(null)} confirmLabel="권한 변경" />}
    <section aria-labelledby="roster-title" className={panelClass}><h2 id="roster-title" className="text-xl font-bold">명단</h2><QueryState pending={roster.isPending || accounts.isPending} error={roster.error ?? accounts.error} retry={() => void refreshDirectory(client)} />{!roster.isPending && !accounts.isPending && !roster.error && !accounts.error && (roster.data?.length ? <ul className="mt-5 grid gap-4 lg:grid-cols-2">{roster.data.map((entry) => <RosterCard key={`${entry.id}:${entry.version}`} entry={entry} accounts={unlinkedAccounts} pending={busy} onRename={(value) => void rename(entry, value)} onLink={(account) => { setRoleTarget(null); setLinkTarget({ entry, account }); }} />)}</ul> : <p className="mt-4 text-sm text-navy-soft">등록된 명단이 없어요.</p>)}</section>
    <section aria-labelledby="accounts-title" className={panelClass}>
      <h2 id="accounts-title" className="text-xl font-bold">가입 계정·전체 관리자 권한</h2>
      <p className="mt-3 text-sm leading-7 text-navy-soft">탈퇴는 개인 예약과 계정 접근을 정리해요. 존속 팀의 예약과 완료 이력은 유지하며, 팀장 후임은 관리자가 직접 지정해 주세요.</p>
      <QueryState pending={accounts.isPending} error={accounts.error} retry={() => void accounts.refetch()} />
      {!accounts.error && accounts.data && <ul className="mt-5 space-y-3">{accounts.data.map((account) => {
        const block = withdrawalBlock(account, accounts.data);
        return <li key={account.id} className="flex flex-wrap items-center justify-between gap-4 rounded-2xl bg-paper p-5">
          <div className="min-w-0"><h3 className="font-bold break-words">{account.displayName}</h3><p className="mt-1 text-sm break-words">{account.loginId} · 계정 #{account.id}</p><p className="mt-2 text-xs leading-6 text-navy-soft">{account.globalRole === "GLOBAL_ADMIN" ? "전체 관리자" : "일반 회원"} · {account.status === "ACTIVE" ? "활성" : account.status === "WITHDRAWN" ? "탈퇴" : "비활성"} · {account.rosterEntryId ? `명단 #${account.rosterEntryId} 연결` : "명단 연결 전"}</p>{block && <p id={`withdraw-block-${account.id}`} className="mt-2 text-xs leading-6 text-navy-soft">{block}</p>}</div>
          <div className="flex flex-wrap gap-2"><button type="button" className={secondaryButton} disabled={busy || account.status !== "ACTIVE"} onClick={() => { setLinkTarget(null); setRoleTarget(account); }}>{account.globalRole === "GLOBAL_ADMIN" ? "관리자 권한 해제" : "관리자 권한 부여"}</button>
            <button type="button" className={secondaryButton} disabled={busy || Boolean(block)} aria-describedby={block ? `withdraw-block-${account.id}` : undefined} onClick={() => { setLinkTarget(null); setRoleTarget(null); setNotice(null); lifecycle.clearFailure(); setWithdrawTarget(account); }}>탈퇴 처리</button></div>
        </li>;
      })}</ul>}
    </section>
    {target && <LifecycleConfirmation target={target} self={target.id === member!.id} stale={lifecycleTargetChanged(target, currentTarget?.version, targetAllowed)} pending={lifecycle.pending} uncertain={lifecycle.uncertain} error={lifecycle.failure ? lifecycleErrorMessage(lifecycle.failure) : null} onConfirm={(reason) => void withdraw(reason)} onRetry={() => void withdraw("", true)} onCancel={() => { setWithdrawTarget(null); lifecycle.clearFailure(); }} />}
  </div>;
}

export default function MembersPage() { return <AppShell><SessionGate admin><MemberManager /></SessionGate></AppShell>; }
