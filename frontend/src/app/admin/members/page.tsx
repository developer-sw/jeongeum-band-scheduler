"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { Confirmation, QueryState, panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";
import { createRosterEntry, linkAccount, updateGlobalRole, updateRosterEntry, updateSignupRole } from "@/features/directory/api";
import { groupMembers, type DirectoryPerson } from "@/features/directory/member-groups";
import { MemberRoleConfirmation, type RoleTarget } from "@/features/directory/member-role-confirmation";
import { membersQuery, refreshDirectory, rosterQuery } from "@/features/directory/queries";
import type { AdminMember, RosterEntry } from "@/features/directory/types";
import { nameError } from "@/features/directory/validation";
import { LifecycleConfirmation, lifecycleResultMessage } from "@/features/lifecycle/confirmation";
import { lifecycleErrorMessage } from "@/features/lifecycle/errors";
import { lifecycleIntent, lifecycleTargetChanged, withdrawalBlock } from "@/features/lifecycle/input";
import { refreshLifecycle } from "@/features/lifecycle/queries";
import { finishSelfWithdrawal } from "@/features/lifecycle/self-withdrawal";
import { useLifecycleAction } from "@/features/lifecycle/use-lifecycle-action";

type RoleNotice = { target: RoleTarget; message: string };

function matchesRoleTarget(person: DirectoryPerson, target: RoleTarget | null) {
  return target?.kind === "ACCOUNT" ? person.account?.id === target.account.id : target?.kind === "SIGNUP" && person.entry?.id === target.entry.id;
}

function MemberCard({ person, accounts, allAccounts, pending, roleTarget, roleNotice, roleError, onConfirmRole, onCancelRole, onRefreshRole, onRename, onLink, onRole, onWithdraw }: {
  person: DirectoryPerson; accounts: AdminMember[]; allAccounts: AdminMember[]; pending: boolean;
  roleTarget: RoleTarget | null; roleNotice: RoleNotice | null; roleError: string | null;
  onConfirmRole: () => void; onCancelRole: () => void; onRefreshRole: () => void;
  onRename: (entry: RosterEntry, name: string) => void;
  onLink: (entry: RosterEntry, account: AdminMember) => void;
  onRole: (target: RoleTarget) => void; onWithdraw: (account: AdminMember) => void;
}) {
  const { entry, account, leaderTeams } = person;
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(entry?.name ?? "");
  const [accountId, setAccountId] = useState("");
  const displayName = entry?.name ?? account?.displayName;
  const assignedAdmin = account ? account.globalRole === "GLOBAL_ADMIN" : !entry?.userId && entry?.signupGlobalRole === "GLOBAL_ADMIN";
  const lastAdmin = account?.status === "ACTIVE" && assignedAdmin && allAccounts.filter((item) => item.status === "ACTIVE" && item.globalRole === "GLOBAL_ADMIN").length <= 1;
  const withdrawBlock = account ? withdrawalBlock(account, allAccounts) : null;
  const roleReady = account ? account.status === "ACTIVE" : Boolean(entry && !entry.userId && entry.signupGlobalRole);
  const blockId = `role-block-${person.key}`;
  const accountStatus = account ? account.status === "ACTIVE" ? "가입 완료" : account.status === "WITHDRAWN" ? "탈퇴" : "비활성" : entry?.userId ? "계정 확인 필요" : "가입 전 명단";
  const selected = matchesRoleTarget(person, roleTarget) ? roleTarget : null;
  const completed = roleNotice && matchesRoleTarget(person, roleNotice.target) ? roleNotice.message : null;
  const stale = selected?.kind === "ACCOUNT"
    ? !account || account.version !== selected.account.version || account.globalRole !== selected.account.globalRole || !roleReady || Boolean(lastAdmin)
    : Boolean(selected && (!entry || entry.version !== selected.entry.version || entry.userId !== null || account || !roleReady));
  const result = useRef<HTMLParagraphElement>(null);

  useEffect(() => {
    if (completed) {
      result.current?.focus({ preventScroll: true });
      result.current?.scrollIntoView({ block: "nearest" });
    }
  }, [completed]);

  return <li aria-busy={pending && Boolean(selected)} className="min-w-0 rounded-2xl border border-navy/15 bg-white p-5">
    <div className="flex flex-wrap items-start justify-between gap-3">
      <div className="min-w-0"><h3 className="font-bold break-words">{displayName}</h3><p className="mt-1 text-xs text-navy-soft">{entry ? `명단 번호 #${entry.id}` : "명단 연결 전"}</p></div>
      <span className="rounded-full bg-paper px-3 py-1 text-xs">{accountStatus}</span>
    </div>
    {account && <p className="mt-3 text-sm break-words">{account.displayName} · {account.loginId} <span className="text-navy-soft">(계정 #{account.id})</span></p>}
    {leaderTeams.length > 0 && <p className="mt-3 text-sm leading-6 break-words text-navy-soft">{assignedAdmin ? "겸임 팀장" : "팀장"}: {leaderTeams.map((team) => team.name).join(" · ")}</p>}
    {!account && entry && !entry.userId && <p className="mt-3 text-sm leading-6 text-navy-soft">{assignedAdmin ? "이 명단의 개인 초대로 가입하면 전체 관리자 권한이 적용돼요." : "가입 전에도 전체 관리자를 미리 지정할 수 있어요."}</p>}
    <div className="mt-4 flex flex-wrap gap-2">
      <button type="button" className={assignedAdmin ? secondaryButton : primaryButton} disabled={pending || Boolean(selected) || !roleReady || Boolean(lastAdmin)} aria-describedby={lastAdmin ? blockId : undefined} onClick={() => {
        if (account) onRole({ kind: "ACCOUNT", account });
        else if (entry && !entry.userId) onRole({ kind: "SIGNUP", entry });
      }}>{assignedAdmin ? "전체 관리자 해제" : "전체 관리자 지정"}</button>
      {entry && <button type="button" className={secondaryButton} disabled={pending} onClick={() => setEditing(!editing)}>{editing ? "이름 수정 닫기" : "명단 이름 수정"}</button>}
    </div>
    {selected && <MemberRoleConfirmation target={selected} pending={pending} stale={stale} error={roleError} onConfirm={onConfirmRole} onCancel={onCancelRole} onRefresh={onRefreshRole} />}
    {completed && <p ref={result} tabIndex={-1} role="status" className="mt-4 rounded-xl bg-paper p-4 text-sm leading-7">{completed}</p>}
    {lastAdmin && <p id={blockId} className="mt-3 text-xs leading-6 text-navy-soft">마지막 활성 전체 관리자의 권한은 해제할 수 없어요.</p>}
    {editing && entry && <form className="mt-4 space-y-3" onSubmit={(event) => { event.preventDefault(); onRename(entry, name); }}><Field id={`roster-name-${entry.id}`} label="명단 이름"><input id={`roster-name-${entry.id}`} className={inputClass} value={name} onChange={(event) => setName(event.target.value)} required disabled={pending} /></Field><button type="submit" className={primaryButton} disabled={pending}>이름 저장</button></form>}
    {entry && !entry.userId && !account && <div className="mt-5 space-y-3"><Field id={`link-account-${entry.id}`} label="이미 가입한 계정 연결" hint="이름으로 자동 연결하지 않아요. 본인의 아이디·계정 번호를 확인해 주세요. 권한은 유지되며, 연결 후 카드에서 ‘전체 관리자 지정’을 할 수 있어요."><select id={`link-account-${entry.id}`} className={inputClass} value={accountId} onChange={(event) => setAccountId(event.target.value)} disabled={pending || !accounts.length} aria-describedby={`link-account-${entry.id}-hint`}><option value="">계정 선택</option>{accounts.map((available) => <option key={available.id} value={available.id}>{available.displayName} · {available.loginId} (#{available.id})</option>)}</select></Field><button type="button" className={secondaryButton} disabled={pending || !accountId} onClick={() => { const selected = accounts.find((item) => item.id === accountId); if (selected) onLink(entry, selected); }}>연결 내용 확인</button></div>}
    {account && <div className="mt-5 border-t border-navy/10 pt-4"><p className="text-xs leading-6 text-navy-soft">탈퇴는 개인 예약과 모든 기기의 로그인을 종료해요. 존속 팀 예약과 완료 이력은 유지돼요.</p>{withdrawBlock && <p id={`withdraw-block-${account.id}`} className="mt-2 text-xs leading-6 text-navy-soft">{withdrawBlock}</p>}<button type="button" className={`${secondaryButton} mt-3`} disabled={pending || Boolean(withdrawBlock)} aria-describedby={withdrawBlock ? `withdraw-block-${account.id}` : undefined} onClick={() => onWithdraw(account)}>탈퇴 처리</button></div>}
  </li>;
}

function MemberManager() {
  const { member, replace } = useSession();
  const client = useQueryClient(); const action = useTransientAction(); const roleAction = useTransientAction(); const lifecycle = useLifecycleAction();
  const roster = useQuery(rosterQuery(member!.id)); const accounts = useQuery(membersQuery(member!.id));
  const [name, setName] = useState(""); const [validation, setValidation] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [linkTarget, setLinkTarget] = useState<{ entry: RosterEntry; account: AdminMember } | null>(null);
  const [roleTarget, setRoleTarget] = useState<RoleTarget | null>(null);
  const [roleNotice, setRoleNotice] = useState<RoleNotice | null>(null);
  const [withdrawTarget, setWithdrawTarget] = useState<AdminMember | null>(null);
  const busy = action.pending || roleAction.pending || withdrawTarget !== null;
  const unlinkedAccounts = (accounts.data ?? []).filter((account) => !account.rosterEntryId && account.status === "ACTIVE");
  const loading = roster.isPending || accounts.isPending;
  const loadError = roster.error ?? accounts.error;
  const groups = groupMembers(roster.data ?? [], accounts.data ?? []);
  const sections = [
    { id: "directory-admins", title: "전체 관리자", description: "전체 관리자가 팀장을 겸해도 이 영역에만 표시해요. 가입 전 지정은 개인 초대 가입 시 적용돼요.", people: groups.admins, empty: "전체 관리자로 지정된 사람이 없어요." },
    { id: "directory-leaders", title: "팀장", description: "전체 관리자를 제외한 현재 팀장이에요. 여러 팀의 팀장이어도 한 번만 표시해요.", people: groups.leaders, empty: "전체 관리자를 제외한 팀장이 없어요." },
    { id: "directory-members", title: "일반 회원", description: "전체 관리자·팀장이 아닌 구성원과 가입 전 명단이에요. 각 카드에서 전체 관리자로 지정할 수 있어요.", people: groups.members, empty: "이 영역에 표시할 구성원이 없어요." },
  ];
  const target = withdrawTarget ? { kind: "WITHDRAW" as const, id: withdrawTarget.id, name: `${withdrawTarget.displayName} · ${withdrawTarget.loginId}`, version: withdrawTarget.version } : null;
  const currentTarget = accounts.data?.find((account) => account.id === withdrawTarget?.id);
  const targetAllowed = !accounts.error && Boolean(currentTarget && !withdrawalBlock(currentTarget, accounts.data ?? []));

  async function withdraw(reason = "", retry = false) {
    if (!target) return;
    const result = retry ? await lifecycle.retry() : await lifecycle.run(lifecycleIntent(target, reason));
    if (result) {
      setWithdrawTarget(null);
      if (target.id === member!.id) { void finishSelfWithdrawal(() => replace(null), () => window.location.replace("/login")); return; }
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
    if (await change((signal) => linkAccount(entry, account.id, signal), "명단과 계정을 연결했어요. 미리 배정한 팀·팀장 역할과 기존 계정의 권한이 유지돼요.")) setLinkTarget(null);
  }
  async function confirmRole() {
    if (!roleTarget || busy) return;
    const selected = roleTarget;
    const assigning = (selected.kind === "ACCOUNT" ? selected.account.globalRole : selected.entry.signupGlobalRole) !== "GLOBAL_ADMIN";
    const nextRole = assigning ? "GLOBAL_ADMIN" : "MEMBER";
    await roleAction.run(async (signal) => {
      if (selected.kind === "SIGNUP") {
        const updated = await updateSignupRole(selected.entry, nextRole, signal);
        client.setQueryData(rosterQuery(member!.id).queryKey, (entries) => entries?.map((entry) => entry.id === updated.id ? updated : entry));
      } else {
        const updated = await updateGlobalRole(selected.account, nextRole, signal);
        client.setQueryData(membersQuery(member!.id).queryKey, (entries) => entries?.map((account) => account.id === updated.id ? updated : account));
      }
      setRoleTarget(null);
      setRoleNotice({ target: selected, message: selected.kind === "SIGNUP"
        ? assigning ? "전체 관리자로 미리 지정했어요. 이 명단의 개인 초대 가입 시 권한이 적용돼요." : "전체 관리자 지정을 해제했어요. 개인 초대 가입 시 일반 회원이 돼요."
        : assigning ? "전체 관리자로 지정했어요. 팀장 역할은 별도로 유지돼요." : "전체 관리자 권한을 해제했어요. 팀장 역할은 별도로 유지돼요." });
      await refreshDirectory(client);
      return true;
    });
  }

  return <div className="space-y-7">
    <section><p className="text-sm font-semibold text-navy-soft">전체 관리자</p><h1 className="mt-2 text-3xl font-extrabold">회원·가입 전 명단 관리</h1><p className="mt-3 text-sm leading-7 text-navy-soft">명단과 가입 계정을 역할별로 함께 관리해요. 전체 관리자·팀장·일반 회원 순서로 한 사람을 한 영역에만 표시해요.</p><div className="mt-4 flex flex-wrap gap-3"><a href="#directory-admins" className={secondaryButton}>전체 관리자 관리</a><Link href="/admin/invitations" className={secondaryButton}>명단별 초대 발급</Link><Link href="/teams" className={secondaryButton}>팀 구성 관리</Link><button type="button" className={secondaryButton} disabled={busy} onClick={() => void refreshDirectory(client)}>목록 새로 불러오기</button></div></section>
    {!loading && !loadError && <nav aria-label="역할별 명단 바로가기" className="grid grid-cols-1 gap-3 sm:grid-cols-3">{sections.map((section) => <a key={section.id} href={`#${section.id}`} className="flex items-center justify-between gap-3 rounded-2xl border border-navy/15 bg-white px-5 py-4 font-bold"><span>{section.title}</span><span className="rounded-full bg-paper px-3 py-1 text-sm tabular-nums">{section.people.length}명</span></a>)}</nav>}
    <form onSubmit={(event) => void create(event)} method="post" className="space-y-4 rounded-3xl bg-paper p-6 sm:p-8" aria-busy={busy}><h2 className="text-xl font-bold">가입 전 명단 등록</h2><Field id="new-roster-name" label="이름" hint="동명이인도 각각 등록할 수 있어요. 연결과 팀 배정은 명단 번호로 구분해요."><input id="new-roster-name" className={inputClass} value={name} onChange={(event) => setName(event.target.value)} required disabled={busy} autoComplete="off" aria-describedby="new-roster-name-hint" /></Field><button type="submit" className={primaryButton} disabled={busy}>{busy ? "처리하는 중…" : "명단 등록"}</button></form>
    <FormError message={validation ?? action.error} />{notice && <p role="status" className="rounded-xl bg-paper p-4 text-sm leading-7">{notice}</p>}
    {linkTarget && <Confirmation title={`${linkTarget.entry.name} 명단 #${linkTarget.entry.id}에 이 계정을 연결할까요?`} description={`${linkTarget.account.displayName} · ${linkTarget.account.loginId} (계정 #${linkTarget.account.id}). 팀·팀장 배정과 현재 계정 권한은 유지되고 미사용 초대는 해지돼요. 연결 후 카드의 ‘전체 관리자 지정’으로 권한을 바꿀 수 있어요. 본인 계정인지 확인해 주세요.`} pending={busy} onConfirm={() => void confirmLink()} onCancel={() => setLinkTarget(null)} confirmLabel="계정 연결" />}
    <QueryState pending={loading} error={loadError} retry={() => void refreshDirectory(client)} />
    {!loading && !loadError && sections.map((section) => <section key={section.id} id={section.id} aria-labelledby={`${section.id}-title`} className={`${panelClass} scroll-mt-6`}>
      <div className="flex flex-wrap items-center gap-3"><h2 id={`${section.id}-title`} className="text-xl font-bold">{section.title}</h2><span className="rounded-full bg-paper px-3 py-1 text-sm font-semibold">{section.people.length}명</span></div>
      <p className="mt-3 text-sm leading-7 text-navy-soft">{section.description}</p>
      {section.people.length > 0 ? <ul className="mt-5 grid items-start gap-4 lg:grid-cols-2">{section.people.map((person) => <MemberCard
        key={`${person.key}:${person.entry?.version}:${person.account?.version}`} person={person} accounts={unlinkedAccounts} allAccounts={accounts.data ?? []} pending={busy}
        roleTarget={roleTarget} roleNotice={roleNotice} roleError={roleAction.error}
        onConfirmRole={() => void confirmRole()}
        onCancelRole={() => { if (!busy) { setRoleTarget(null); roleAction.clearError(); } }}
        onRefreshRole={() => { if (!busy) void roleAction.run(async () => { await refreshDirectory(client); return true; }); }}
        onRename={(entry, value) => void rename(entry, value)}
        onLink={(entry, account) => { setRoleTarget(null); roleAction.clearError(); setLinkTarget({ entry, account }); }}
        onRole={(selection) => { if (!busy) { setLinkTarget(null); setRoleNotice(null); roleAction.clearError(); action.clearError(); setValidation(null); setNotice(null); setRoleTarget(selection); } }}
        onWithdraw={(account) => { setLinkTarget(null); setRoleTarget(null); roleAction.clearError(); setNotice(null); lifecycle.clearFailure(); setWithdrawTarget(account); }}
      />)}</ul> : <p className="mt-5 rounded-2xl bg-paper p-5 text-sm text-navy-soft">{section.empty}</p>}
    </section>)}
    {target && <LifecycleConfirmation target={target} self={target.id === member!.id} stale={lifecycleTargetChanged(target, currentTarget?.version, targetAllowed)} pending={lifecycle.pending} uncertain={lifecycle.uncertain} error={lifecycle.failure ? lifecycleErrorMessage(lifecycle.failure) : null} onConfirm={(reason) => void withdraw(reason)} onRetry={() => void withdraw("", true)} onCancel={() => { setWithdrawTarget(null); lifecycle.clearFailure(); }} />}
  </div>;
}

export default function MembersPage() { return <AppShell><SessionGate admin><MemberManager /></SessionGate></AppShell>;
}
