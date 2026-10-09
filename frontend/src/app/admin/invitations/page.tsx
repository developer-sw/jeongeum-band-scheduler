"use client";

import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import Link from "next/link";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { getInvitations, issueInvitation, revokeInvitation } from "@/features/auth/api";
import { authErrorMessage } from "@/features/auth/errors";
import { displayNameError } from "@/features/auth/validation";
import { useTransientAction } from "@/features/auth/use-transient-action";
import type { Invitation, IssuedInvitation } from "@/features/auth/types";
import { useEphemeralState } from "@/features/auth/use-ephemeral-state";
import { useSession } from "@/features/auth/session-provider";
import { refreshDirectory, rosterQuery } from "@/features/directory/queries";
import { QueryState } from "@/components/directory-ui";
import { invitationInput } from "@/features/auth/invitation-input";

const labels: Record<Invitation["status"], string> = { AVAILABLE: "사용 가능", USED: "가입 완료", EXPIRED: "만료", REVOKED: "해지" };
const dateLabel = (value: string) => new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "short", timeZone: "Asia/Seoul" }).format(new Date(value));

function InvitationManager() {
  const client = useQueryClient(); const action = useTransientAction();
  const { member } = useSession(); const roster = useQuery(rosterQuery(member!.id));
  const [rosterEntryId, setRosterEntryId] = useState(""); const [expiresAt, setExpiresAt] = useState("");
  const [issued, setIssued] = useEphemeralState<IssuedInvitation | null>(null); const [copyMessage, setCopyMessage] = useEphemeralState<string | null>(null);
  const [validation, setValidation] = useState<string | null>(null); const [revokeTarget, setRevokeTarget] = useState<Invitation | null>(null);
  const query = useInfiniteQuery({
    queryKey: ["admin", "invitations"], initialPageParam: null as string | null,
    queryFn: ({ pageParam, signal }) => getInvitations(pageParam, signal),
    getNextPageParam: (page) => page?.meta.nextCursor ?? undefined,
    retry: false,
  });
  const pendingRoster = (roster.data ?? []).filter((entry) => !entry.userId);

  async function issue(event?: React.FormEvent<HTMLFormElement>, replacement?: Invitation) {
    event?.preventDefault(); const selected = pendingRoster.find((entry) => entry.id === rosterEntryId);
    if (!replacement && !selected) { setValidation("초대할 가입 전 명단을 선택해 주세요."); return; }
    const name = replacement?.recipientName ?? selected!.name;
    const issue = displayNameError(name);
    if (issue) { setValidation(issue); return; }
    let expiration: string | undefined;
    if (expiresAt && !replacement) {
      const date = new Date(expiresAt);
      if (!Number.isFinite(date.getTime()) || date.getTime() <= Date.now()) { setValidation("만료 시각은 현재 이후로 선택해 주세요."); return; }
      expiration = date.toISOString();
    }
    setValidation(null); setIssued(null); setCopyMessage(null);
    const result = await action.run((signal) => issueInvitation(invitationInput(selected, replacement, expiration), signal));
    if (result) { setIssued(result); setRosterEntryId(""); setExpiresAt(""); await refreshDirectory(client); }
  }

  async function revoke() {
    if (!revokeTarget) return;
    const done = await action.run(async (signal) => { await revokeInvitation(revokeTarget.id, signal); return true; });
    setRevokeTarget(null);
    if (done) { setIssued(null); await refreshDirectory(client); }
  }

  async function copy() {
    if (!issued) return;
    try { await navigator.clipboard.writeText(issued.token); setCopyMessage("초대 코드를 복사했어요."); }
    catch { setCopyMessage("복사하지 못했어요. 아래 코드를 직접 선택해 복사해 주세요."); }
  }

  return <div className="space-y-7">
    <section><p className="text-sm font-semibold text-navy-soft">전체 관리자</p><h1 className="mt-2 text-3xl font-extrabold">개인 초대 관리</h1><p className="mt-3 text-sm leading-7 text-navy-soft">명단 번호에 연결된 일회용 초대로 가입하면 미리 배정한 팀·팀장 역할이 계정에 연결돼요. 발급한 코드는 이 화면에서 한 번만 확인할 수 있어요.</p><Link href="/admin/members" className={`${secondaryButton} mt-4 inline-block`}>가입 전 명단 등록·계정 연결</Link></section>
    <form method="post" onSubmit={(event) => void issue(event)} className="grid gap-5 rounded-3xl bg-paper p-6 sm:grid-cols-2 sm:p-8" aria-busy={action.pending}>
      <Field id="invitation-roster" label="초대할 가입 전 명단" hint="동명이인은 명단 번호로 구분해요. 재발급할 때도 같은 명단 연결을 유지해요."><select id="invitation-roster" className={inputClass} required value={rosterEntryId} onChange={(event) => setRosterEntryId(event.target.value)} disabled={action.pending || roster.isPending || Boolean(roster.error) || !pendingRoster.length} aria-describedby="invitation-roster-hint"><option value="">명단 선택</option>{pendingRoster.map((entry) => <option key={entry.id} value={entry.id}>{entry.name} (명단 #{entry.id})</option>)}</select></Field>
      <Field id="expires-at" label="만료 시각 (선택)" hint="선택하지 않으면 발급 후 7일. 시각은 현재 기기의 시간대를 기준으로 입력해요."><input id="expires-at" type="datetime-local" className={inputClass} value={expiresAt} onChange={(event) => setExpiresAt(event.target.value)} aria-describedby="expires-at-hint" disabled={action.pending} /></Field>
      <div className="sm:col-span-2"><QueryState pending={roster.isPending} error={roster.error} retry={() => void roster.refetch()} />{!roster.isPending && !roster.error && !pendingRoster.length && <p className="mb-3 text-sm leading-7">초대할 명단이 없어요. 회원 관리에서 가입 전 명단을 먼저 등록해 주세요.</p>}<FormError message={validation ?? action.error} /></div><button type="submit" className={`${primaryButton} justify-self-start`} disabled={action.pending || !rosterEntryId || Boolean(roster.error)}>{action.pending ? "처리하는 중…" : "개인 초대 발급"}</button>
    </form>
    {issued && <section aria-labelledby="issued-title" className="rounded-3xl border-2 border-yellow p-6 sm:p-8"><h2 id="issued-title" className="text-lg font-bold">{issued.recipientName}님의 새 초대</h2><p className="mt-2 text-sm leading-7">명단 #{issued.rosterEntryId ?? "미연결"} · 만료: {dateLabel(issued.expiresAt)}. 화면을 닫거나 이동하면 코드를 다시 볼 수 없어요. 필요한 사람에게 직접 전달해 주세요.</p><label htmlFor="issued-token" className="mt-5 mb-2 block text-sm font-semibold">초대 코드</label><textarea id="issued-token" readOnly autoComplete="off" spellCheck={false} rows={2} value={issued.token} className={`${inputClass} break-all font-mono text-sm`} /><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} onClick={() => void copy()}>초대 코드 복사</button><button type="button" className={secondaryButton} onClick={() => { setIssued(null); setCopyMessage(null); }}>코드 닫기</button></div>{copyMessage && <p role="status" className="mt-3 text-sm">{copyMessage}</p>}</section>}
    {revokeTarget && <section role="alert" className="rounded-xl border border-navy/20 p-5"><h2 className="font-bold">{revokeTarget.recipientName}님의 초대를 해지할까요?</h2><p className="mt-2 text-sm">해지하면 이 코드로 가입할 수 없어요.</p><div className="mt-4 flex gap-3"><button type="button" className={primaryButton} disabled={action.pending} onClick={() => void revoke()}>초대 해지</button><button type="button" className={secondaryButton} disabled={action.pending} onClick={() => setRevokeTarget(null)}>취소</button></div></section>}
    <section aria-labelledby="list-title"><h2 id="list-title" className="mb-5 text-xl font-bold">초대 목록</h2>{query.isPending ? <p role="status">목록을 불러오고 있어요.</p> : query.isError ? <><FormError message={authErrorMessage(query.error)} /><button type="button" className={`${secondaryButton} mt-4`} onClick={() => void query.refetch()}>다시 불러오기</button></> : <>
      {query.data.pages.every((page) => !page?.data.items.length) ? <p className="rounded-xl bg-paper p-5 text-sm text-navy-soft">아직 발급한 초대가 없어요.</p> : <ul className="space-y-3">{query.data.pages.flatMap((page) => page?.data.items ?? []).map((item) => <li key={item.id} className="flex flex-wrap items-center justify-between gap-4 rounded-2xl border border-navy/15 p-5"><div className="min-w-0"><h3 className="font-semibold break-words">{item.recipientName} <span className="ml-2 rounded-full bg-paper px-3 py-1 text-xs font-medium">{labels[item.status]}</span></h3><p className="mt-2 text-xs leading-6 text-navy-soft">초대 #{item.id} · 명단 {item.rosterEntryId ? `#${item.rosterEntryId}` : "이전 초대·미연결"} · 발급 {dateLabel(item.createdAt)} · 만료 {dateLabel(item.expiresAt)}{item.usedBy && ` · 가입 계정 #${item.usedBy}`}</p></div>{(item.status === "AVAILABLE" || item.status === "EXPIRED") && <div className="flex flex-wrap gap-2"><button type="button" className={secondaryButton} disabled={action.pending} onClick={() => void issue(undefined, item)} aria-label={`${item.recipientName} 초대 #${item.id} 재발급`}>재발급</button><button type="button" className={secondaryButton} disabled={action.pending} onClick={() => setRevokeTarget(item)} aria-label={`${item.recipientName} 초대 #${item.id} 해지`}>해지</button></div>}</li>)}</ul>}
      {query.hasNextPage && <button type="button" className={`${secondaryButton} mt-5`} disabled={query.isFetchingNextPage} onClick={() => void query.fetchNextPage()}>{query.isFetchingNextPage ? "불러오는 중…" : "더 보기"}</button>}
    </>}</section>
  </div>;
}

export default function InvitationsPage() { return <AppShell><SessionGate admin><InvitationManager /></SessionGate></AppShell>; }
