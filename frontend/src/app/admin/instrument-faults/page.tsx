"use client";

import Link from "next/link";
import { useRef, useState } from "react";
import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, secondaryButton, primaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { FaultEditor } from "@/features/instrument-faults/editor";
import { faultErrorMessage, staleFaultReloadNotice } from "@/features/instrument-faults/errors";
import { faultSelectionChanged } from "@/features/instrument-faults/input";
import { currentFaultsQuery, faultHistoryQuery, instrumentsQuery, refreshInstrumentFaults } from "@/features/instrument-faults/queries";
import { useFaultAction } from "@/features/instrument-faults/use-fault-action";
import { FaultHistoryCard, InstrumentStatusCard } from "@/features/instrument-faults/view";
import type { FaultEditorSelection, FaultIntent, InstrumentFault } from "@/features/instrument-faults/types";

function FaultManager({ scope }: { scope: string }) {
  const client = useQueryClient(); const action = useFaultAction();
  const assets = useQuery(instrumentsQuery(scope)); const current = useQuery(currentFaultsQuery(scope));
  const [filter, setFilter] = useState(""); const history = useInfiniteQuery(faultHistoryQuery(scope, filter));
  const [editor, setEditor] = useState<FaultEditorSelection | null>(null); const [notice, setNotice] = useState<string | null>(null);
  const returnFocus = useRef<HTMLElement | null>(null);
  const busy = action.pending || action.uncertain; const failure = assets.error ?? current.error;
  const stale = Boolean(editor && assets.data && current.data && faultSelectionChanged(editor, assets.data, current.data));
  const incidents = history.data?.pages.flatMap((page) => page.incidents) ?? [];
  const uniqueIncidents = incidents.filter((incident, index) => incidents.findIndex((item) => item.id === incident.id) === index);
  function select(selection: FaultEditorSelection) {
    if (busy) return;
    returnFocus.current = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    setEditor(structuredClone(selection)); setNotice(null); action.clearFailure();
  }
  function close() { setEditor(null); returnFocus.current?.focus(); }
  async function completed(result?: InstrumentFault) {
    if (result) { close(); setNotice(`${result.instrumentName} ${result.status === "OPEN" ? "고장 등록" : "복구 완료"} 결과를 확인했어요. 기존 예약은 유지돼요.`); }
    await refreshInstrumentFaults(client);
  }
  async function apply(intent: FaultIntent) { setNotice(null); await completed(await action.run(intent)); }
  async function completedAfterRetry() { await completed(await action.retry()); }
  async function reload() {
    const recovery = staleFaultReloadNotice(action.failure, action.uncertain);
    if (!busy && (recovery || stale)) { close(); action.clearFailure(); setNotice(recovery ?? "최신 악기 상태를 확인한 뒤 등록 또는 복구를 다시 선택해 주세요."); }
    await refreshInstrumentFaults(client);
  }
  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">전체 관리자</p><h1 className="mt-2 text-3xl font-extrabold">악기 고장 관리</h1><p className="mt-3 text-sm leading-7 text-navy-soft">악기별 고장과 복구를 기록해요. 합주실 전체를 닫지 않으며 기존 예약은 자동으로 취소하지 않아요.</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={secondaryButton} disabled={action.pending || assets.isFetching || current.isFetching} onClick={() => void reload()}>최신 상태 새로 확인</button><Link href="/reservations" className={secondaryButton}>합주실 시간표</Link></div></section>
    <FormError message={action.failure ? faultErrorMessage(action.failure) : null} />
    {action.uncertain && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold">처리 결과를 아직 확인하지 못했어요</h2><p className="mt-2 text-sm leading-7">응답을 받기 전에 적용됐을 수 있어요. 같은 내용과 요청 번호로 결과를 다시 확인해 주세요. 확인 전에는 다른 고장 등록·복구를 보내지 않아요.</p><p className="mt-2 text-xs leading-6 text-navy-soft">이 요청은 현재 화면에 보관돼요. 페이지를 나가거나 새로고침했다면 최신 악기 상태와 이력을 먼저 확인해 주세요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={action.pending} onClick={() => void completedAfterRetry()}>{action.pending ? "요청 확인 중…" : "같은 요청 결과 다시 확인"}</button></section>}
    {notice && <p role="status" className="rounded-xl bg-paper p-5 text-sm leading-7">{notice}</p>}
    {(assets.isPending || current.isPending) && <p role="status" className="py-6 text-navy-soft">악기와 현재 고장 상태를 확인하고 있어요.</p>}
    {failure && <div className="space-y-4"><FormError message={faultErrorMessage(failure)} /><button type="button" className={secondaryButton} onClick={() => void reload()}>상태 다시 불러오기</button></div>}
    {editor && <FaultEditor key={`${scope}:${editor.kind}:${editor.instrument.code}:${editor.instrument.version}`} selection={editor} disabled={busy || Boolean(failure)} stale={stale} uncertain={action.uncertain} onApply={(intent) => void apply(intent)} onClose={close} />}
    {!failure && assets.data && current.data && <section className={panelClass} aria-labelledby="instrument-status-title"><h2 id="instrument-status-title" className="text-xl font-bold">현재 악기 상태</h2><p className="mt-3 text-sm leading-7 text-navy-soft">상태는 20초마다 확인해요. 고장 악기를 새로 배치하는 개인 예약·변경·교환은 제한하고, 팀 합주에는 고장을 안내해요.</p><ul className="mt-5 grid items-start gap-4 md:grid-cols-2 xl:grid-cols-3">{assets.data.map((instrument) => <InstrumentStatusCard key={instrument.code} instrument={instrument} incident={current.data.find((incident) => incident.instrumentCode === instrument.code)} disabled={busy || assets.isFetching || current.isFetching} onSelect={select} />)}</ul></section>}
    <section className={panelClass} aria-labelledby="instrument-history-title"><h2 id="instrument-history-title" className="text-xl font-bold">고장·복구 이력</h2><p className="mt-3 text-sm leading-7 text-navy-soft">최근 등록 순서로 표시해요. 복구한 이력도 보존하며 시간은 서울 기준이에요.</p><div className="mt-5 max-w-sm"><Field id="fault-history-filter" label="이력의 악기"><select id="fault-history-filter" className={inputClass} value={filter} onChange={(event) => setFilter(event.target.value)}><option value="">전체 악기</option>{assets.data?.map((instrument) => <option key={instrument.code} value={instrument.code}>{instrument.name}</option>)}</select></Field></div>
      {history.isPending && <p role="status" className="mt-5 text-sm text-navy-soft">이력을 불러오고 있어요.</p>}{history.error && <div className="mt-5 space-y-4"><FormError message={faultErrorMessage(history.error)} /><button type="button" className={secondaryButton} onClick={() => void history.refetch()}>이력 다시 불러오기</button></div>}
      {history.data && <>{uniqueIncidents.length ? <ul className="mt-5 grid items-start gap-4 lg:grid-cols-2">{uniqueIncidents.map((incident) => <FaultHistoryCard key={incident.id} incident={incident} />)}</ul> : <p role="status" className="mt-5 text-sm leading-7 text-navy-soft">{filter ? "이 악기의 고장·복구 이력이 없어요." : "아직 고장·복구 이력이 없어요."}</p>}{history.hasNextPage && <button type="button" className={`${secondaryButton} mt-5`} disabled={history.isFetching} onClick={() => void history.fetchNextPage()}>{history.isFetchingNextPage ? "이전 이력 확인 중…" : "이전 이력 더 보기"}</button>}</>}
    </section>
  </div>;
}
function FaultWorkspace() { const { member } = useSession(); const scope = directoryScope(member!); return <FaultManager key={scope} scope={scope} />; }
export default function InstrumentFaultsPage() { return <AppShell><SessionGate admin><FaultWorkspace /></SessionGate></AppShell>; }
