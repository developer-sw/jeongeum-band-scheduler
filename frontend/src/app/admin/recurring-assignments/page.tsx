"use client";

import Link from "next/link";
import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { FormError, SessionGate, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope, teamsQuery } from "@/features/directory/queries";
import { seoulDate } from "@/features/reservations/time";
import { AssignmentEditor, AssignmentEndPanel } from "@/features/recurring/editor";
import { assignmentConflicts, assignmentErrorMessage, staleAssignmentReloadNotice } from "@/features/recurring/errors";
import { assignmentsQuery, refreshAssignments } from "@/features/recurring/queries";
import { useAssignmentAction } from "@/features/recurring/use-assignment-action";
import { AssignmentCard, ConflictList } from "@/features/recurring/view";
import type { Assignment, AssignmentIntent } from "@/features/recurring/types";

function AssignmentManager({ scope }: { scope: string }) {
  const client = useQueryClient(); const action = useAssignmentAction();
  const query = useQuery(assignmentsQuery(scope)); const teams = useQuery(teamsQuery(scope));
  const [editor, setEditor] = useState<{ assignment: Assignment | null } | null>(null); const [ending, setEnding] = useState<Assignment | null>(null); const [notice, setNotice] = useState<string | null>(null);
  const snapshot = query.data; const today = snapshot ? seoulDate(Date.parse(snapshot.serverTime)) : "";
  const busy = action.pending || action.uncertain; const failure = query.error ?? teams.error;
  function openEditor(assignment: Assignment | null) { setEditor({ assignment: assignment && structuredClone(assignment) }); setEnding(null); setNotice(null); action.clearFailure(); }
  function openEnd(assignment: Assignment) { setEnding(structuredClone(assignment)); setEditor(null); setNotice(null); action.clearFailure(); }
  async function completed(result?: Assignment) {
    if (result) { setNotice(result.status === "ENDED" ? `고정 배정 #${result.id}의 종료일 지정 상태를 확인했어요. 종료일 당일까지 배정이 유지돼요.` : `고정 배정 #${result.id}을 적용했어요. 회차별 예외는 유지돼요.`); setEditor(null); setEnding(null); }
    await refreshAssignments(client);
  }
  async function apply(intent: AssignmentIntent) { setNotice(null); await completed(await action.run(intent)); }
  async function retry() { await completed(await action.retry()); }
  async function reload() {
    const recoveryNotice = staleAssignmentReloadNotice(action.failure, action.uncertain);
    if (recoveryNotice) { setEditor(null); setEnding(null); action.clearFailure(); setNotice(recoveryNotice); }
    await Promise.all([refreshAssignments(client), teams.refetch()]);
  }
  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">전체 관리자</p><h1 className="mt-2 text-3xl font-extrabold">주간 고정 배정</h1><p className="mt-3 text-sm leading-7 text-navy-soft">정규조·추가조의 매주 합주 시간을 배정해요. 한국 시간 00시에 새 날짜의 고정 회차를 먼저 반영하고 남은 시간을 선착순으로 공개해요.</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={busy || !snapshot || Boolean(failure) || teams.isPending} onClick={() => openEditor(null)}>새 고정 배정</button><button type="button" className={secondaryButton} disabled={action.pending} onClick={() => void reload()}>배정·시간표 새로 확인</button><Link href="/reservations" className={secondaryButton}>합주실 시간표</Link><Link href="/teams" className={secondaryButton}>팀 구성 관리</Link></div></section>
    <FormError message={action.failure ? assignmentErrorMessage(action.failure) : null} /><ConflictList conflicts={assignmentConflicts(action.failure)} />{action.uncertain && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold">배정 요청 결과를 아직 확인하지 못했어요</h2><p className="mt-2 text-sm leading-7">응답을 받기 전에 적용됐을 수 있어요. 목록을 확인하거나 같은 내용과 요청 번호로 결과를 다시 확인해 주세요. 확인 전에는 새 배정 요청을 보내지 않아요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={action.pending} onClick={() => void retry()}>{action.pending ? "요청 확인 중…" : "같은 요청 결과 다시 확인"}</button></section>}{notice && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">{notice}</p>}
    {(query.isPending || teams.isPending) && <p role="status" className="py-6 text-navy-soft">고정 배정과 팀을 확인하고 있어요.</p>}{failure && <div className="space-y-4"><FormError message={assignmentErrorMessage(failure)} /><button type="button" className={secondaryButton} onClick={() => void reload()}>다시 불러오기</button></div>}
    {!failure && snapshot && teams.data && <>{!teams.data.some((team) => team.status === "ACTIVE") && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">먼저 팀 구성 관리에서 정규조·추가조를 만들어 주세요.</p>}{editor && <AssignmentEditor key={editor.assignment ? `${scope}:${editor.assignment.id}:${editor.assignment.version}` : `${scope}:new`} assignment={editor.assignment} teams={teams.data} today={today} disabled={busy} onApply={(intent) => void apply(intent)} onClose={() => setEditor(null)} />}{ending && <AssignmentEndPanel key={`${scope}:${ending.id}:${ending.version}`} assignment={ending} today={today} disabled={busy} onEnd={(input) => void apply({ kind: "END", assignmentId: ending.id, input })} onClose={() => setEnding(null)} />}
      <section className={panelClass} aria-labelledby="assignments-title"><h2 id="assignments-title" className="text-xl font-bold">반복 배정과 회차</h2><p className="mt-3 text-sm leading-7 text-navy-soft">목록은 20초마다 확인해요. 이 날짜의 회차를 취소하려면 합주실 시간표에서 처리하세요. 회차 취소는 다음 주 패턴을 바꾸지 않아요.</p>{snapshot.assignments.length ? <ul className="mt-5 grid items-start gap-4 lg:grid-cols-2">{snapshot.assignments.map((assignment) => <AssignmentCard key={assignment.id} assignment={assignment} today={today} disabled={busy} onEdit={openEditor} onEnd={openEnd} />)}</ul> : <p className="mt-5 text-sm leading-7 text-navy-soft">아직 등록한 고정 배정이 없어요.</p>}</section></>}
  </div>;
}

function AssignmentWorkspace() { const { member } = useSession(); const scope = directoryScope(member!); return <AssignmentManager key={scope} scope={scope} />; }
export default function RecurringAssignmentsPage() { return <AppShell><SessionGate admin><AssignmentWorkspace /></SessionGate></AppShell>; }
