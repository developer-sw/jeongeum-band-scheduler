"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { QueryState, panelClass, teamKindLabel } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { useTransientAction } from "@/features/auth/use-transient-action";
import { createTeam } from "@/features/directory/api";
import { directoryScope, refreshDirectory, teamsQuery } from "@/features/directory/queries";
import type { TeamKind } from "@/features/directory/types";
import { nameError } from "@/features/directory/validation";

function TeamList() {
  const { member } = useSession(); const client = useQueryClient(); const action = useTransientAction();
  const query = useQuery(teamsQuery(directoryScope(member!)));
  const [name, setName] = useState(""); const [kind, setKind] = useState<TeamKind>("REGULAR");
  const [validation, setValidation] = useState<string | null>(null); const [notice, setNotice] = useState<string | null>(null);
  async function create(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); const error = nameError(name, 80, "팀 이름");
    if (error) { setValidation(error); return; }
    setValidation(null); setNotice(null);
    const team = await action.run((signal) => createTeam(name.trim(), kind, signal));
    if (team) { setName(""); setNotice(`${team.name} 팀을 만들었어요. 팀을 열어 명단과 팀장을 배정해 주세요.`); await refreshDirectory(client); }
  }
  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">함께 연주하는 사람들</p><h1 className="mt-2 text-3xl font-extrabold">팀·곡 목록</h1><p className="mt-3 text-sm leading-7 text-navy-soft">여러 정규조와 추가조에 함께 소속될 수 있어요. 팀 구성은 전체 관리자가, 곡 목록은 전체 관리자와 해당 팀장이 관리해요.</p><button type="button" className={`${secondaryButton} mt-4`} onClick={() => void refreshDirectory(client)} disabled={action.pending}>목록 새로 불러오기</button></section>
    {member?.globalRole === "GLOBAL_ADMIN" && <form onSubmit={(event) => void create(event)} method="post" className="grid gap-5 rounded-3xl bg-paper p-6 sm:grid-cols-2 sm:p-8" aria-busy={action.pending}><h2 className="text-xl font-bold sm:col-span-2">팀 만들기</h2><Field id="new-team-name" label="팀 이름"><input id="new-team-name" className={inputClass} value={name} onChange={(event) => setName(event.target.value)} required disabled={action.pending} /></Field><Field id="new-team-kind" label="팀 구분"><select id="new-team-kind" className={inputClass} value={kind} onChange={(event) => setKind(event.target.value as TeamKind)} disabled={action.pending}><option value="REGULAR">정규조</option><option value="ADDITIONAL">추가조</option></select></Field><button type="submit" className={`${primaryButton} justify-self-start`} disabled={action.pending}>{action.pending ? "처리하는 중…" : "팀 생성"}</button></form>}
    <FormError message={validation ?? action.error} />{notice && <p role="status" className="rounded-xl bg-paper p-4 text-sm leading-7">{notice}</p>}
    <section className={panelClass}><h2 className="text-xl font-bold">전체 팀</h2><QueryState pending={query.isPending} error={query.error} retry={() => void query.refetch()} />{!query.error && query.data && (query.data.filter((team) => team.status === "ACTIVE").length ? <ul className="mt-5 grid gap-4 sm:grid-cols-2">{query.data.filter((team) => team.status === "ACTIVE").map((team) => { const own = member?.teamMemberships.find((membership) => membership.teamId === team.id); return <li key={team.id}><Link href={`/teams/${encodeURIComponent(team.id)}`} className="block h-full rounded-2xl bg-paper p-6"><div className="flex flex-wrap items-center gap-2"><span className="text-xs text-navy-soft">{teamKindLabel(team.kind)}</span>{own && <span className="rounded-full bg-yellow px-3 py-1 text-xs font-bold">{own.role === "LEADER" ? "내 팀 · 팀장" : "내 팀"}</span>}</div><h3 className="mt-3 text-lg font-bold break-words">{team.name}</h3><p className="mt-3 text-sm text-navy-soft">팀 구성과 연습곡 보기 →</p></Link></li>; })}</ul> : <p className="mt-5 text-sm text-navy-soft">아직 만들어진 팀이 없어요.</p>)}</section>
  </div>;
}

export default function TeamsPage() { return <AppShell><SessionGate><TeamList /></SessionGate></AppShell>; }
