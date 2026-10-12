"use client";

import { useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { FormError, primaryButton, secondaryButton } from "@/components/auth-ui";
import { panelClass } from "@/components/directory-ui";
import type { Member } from "@/features/auth/types";
import { scheduleQuery } from "@/features/reservations/queries";
import { useServerClock } from "@/features/reservations/use-server-clock";
import { CreateExchangeForm } from "./create-form";
import { ExchangeDetail } from "./detail";
import { exchangeErrorMessage } from "./errors";
import { exchangeCandidatesQuery, exchangeQuery, exchangesQuery, refreshExchanges } from "./queries";
import { exchangeStatusLabels } from "./selection";
import type { ExchangeIntent, ExchangeResponse } from "./types";
import { useExchangeAction } from "./use-exchange-action";

function RequestDetail({ scope, id, member, now, disabled, onAction, onClose }: { scope: string; id: string; member: Member; now: number; disabled: boolean; onAction: (intent: ExchangeIntent) => void; onClose: () => void }) {
  const query = useQuery(exchangeQuery(scope, id));
  return <section className={panelClass} aria-label="선택한 교환 상세"><FormError message={query.error ? exchangeErrorMessage(query.error) : null} />{query.isPending && <p role="status">교환 상세를 확인하고 있어요.</p>}{query.data && <ExchangeDetail key={query.data.id} request={query.data} member={member} now={now} disabled={disabled || query.isFetching || query.isError} onAction={onAction} />}<div className="mt-5 flex flex-wrap gap-3"><button type="button" className={secondaryButton} disabled={disabled || query.isFetching} onClick={() => void query.refetch()}>최신 요청·미리보기 확인</button><button type="button" className={secondaryButton} disabled={disabled} onClick={onClose}>상세 닫기</button></div></section>;
}
export function ExchangeBoard({ member, scope, initialReservationId, initialId }: { member: Member; scope: string; initialReservationId: string; initialId: string }) {
  const client = useQueryClient(); const action = useExchangeAction();
  const schedule = useQuery(scheduleQuery(scope)); const now = useServerClock(schedule.data);
  const list = useQuery(exchangesQuery(scope));
  const candidates = useQuery({ ...exchangeCandidatesQuery(scope, schedule.data?.today ?? "", schedule.data?.bookableThrough ?? ""), enabled: Boolean(schedule.data) });
  const [id, setId] = useState(initialId); const [tab, setTab] = useState<"SENT" | "RECEIVED" | "ALL">("RECEIVED");
  const [createOpen, setCreateOpen] = useState(Boolean(initialReservationId)); const [createNonce, setCreateNonce] = useState(0);
  const [refreshing, setRefreshing] = useState(false); const [notice, setNotice] = useState<string | null>(null);
  const busy = action.pending || action.uncertain || refreshing;
  async function refresh() { setRefreshing(true); try { await refreshExchanges(client); } finally { setRefreshing(false); } }
  async function completed(result: ExchangeResponse | undefined) {
    if (result) { client.setQueryData(exchangeQuery(scope, result.id).queryKey, result); setId(result.id); setNotice(result.status === "COMPLETED" ? "양측 교환이 완료됐어요. 시간표·내 예약·내 공연조 일정에서 새 예약을 확인해 주세요." : `교환 요청 상태: ${exchangeStatusLabels[result.status]}. 원래 예약은 유지돼요.`); setCreateOpen(false); setCreateNonce((value) => value + 1); }
    await refresh();
  }
  async function run(intent: ExchangeIntent) { if (!busy) await completed(await action.run(intent)); }
  async function retry() { if (!action.pending && !refreshing) await completed(await action.retry()); }
  const requests = (list.data?.requests ?? []).filter((item) => tab === "ALL" || (tab === "SENT" ? item.requesterUserId === member.id : item.requesterUserId !== member.id));
  return <div className="space-y-6"><section><p className="text-sm font-semibold text-navy-soft">STUDIO NOTE</p><h1 className="mt-2 text-3xl font-extrabold">예약 교환</h1><p className="mt-3 text-sm leading-7 text-navy-soft">각자의 예약 주체·예약자·악기를 유지하며 시작 전 예약의 시간만 교환해요. 대기 중 원래 예약은 그대로예요.</p><div className="mt-4 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={busy} onClick={() => setCreateOpen((value) => !value)}>{createOpen ? "요청 작성 닫기" : "새 교환 요청"}</button><button type="button" className={secondaryButton} disabled={action.pending || refreshing} onClick={() => void refresh()}>예약·교환 새로 확인</button></div></section>
    <FormError message={action.error} />{action.uncertain && <section role="alert" className="rounded-2xl border border-yellow bg-yellow/10 p-5"><h2 className="font-bold">교환 요청 결과를 아직 확인하지 못했어요</h2><p className="mt-2 text-sm leading-7">이미 처리됐을 수 있어요. 같은 본문·키로 직접 결과를 재확인해 주세요. 확인 전에는 다른 쓰기 요청을 보내지 않아요. 새로고침·화면 이동 후에는 최신 목록과 예약 이력부터 확인해 주세요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={action.pending || refreshing} onClick={() => void retry()}>같은 교환 요청 결과 다시 확인</button></section>}{notice && <p role="status" className="rounded-xl bg-paper p-4 text-sm leading-7">{notice}</p>}
    <FormError message={schedule.error ? exchangeErrorMessage(schedule.error) : null} />
    {createOpen && <section className={panelClass} aria-label="새 교환 요청 작성"><h2 className="mb-4 text-xl font-bold">교환할 양쪽 예약 선택</h2>{candidates.isPending && <p role="status">교환 후보 예약을 확인하고 있어요.</p>}<FormError message={candidates.error ? exchangeErrorMessage(candidates.error) : null} />{candidates.data && now > 0 && <CreateExchangeForm key={`${scope}:${createNonce}`} candidates={candidates.data.reservations} now={now} initialId={createNonce === 0 ? initialReservationId : ""} disabled={busy || candidates.isError || candidates.isFetching || schedule.isError} onCreate={(input) => void run({ kind: "CREATE", input })} />}</section>}
    {id && <RequestDetail key={`${scope}:${id}`} scope={scope} id={id} member={member} now={now} disabled={busy || !now || schedule.isError} onAction={(intent) => void run(intent)} onClose={() => setId("")} />}
    <section className={panelClass} aria-label="관련 교환 요청 목록"><h2 className="text-xl font-bold">내 개인·소속 공연조의 교환 요청</h2><p className="mt-3 text-sm leading-7 text-navy-soft">관련 팀원도 요청을 조회할 수 있어요. 승인·거절·취소 버튼은 서버의 현재 권한을 따라 표시해요.</p><div className="mt-4 flex flex-wrap gap-2" role="group" aria-label="교환 요청 목록 필터">{([{ value: "RECEIVED", label: "받은·관련 요청" }, { value: "SENT", label: "보낸 요청" }, { value: "ALL", label: "전체 관련 요청" }] as const).map((item) => <button key={item.value} type="button" className={secondaryButton} aria-pressed={tab === item.value} disabled={busy} onClick={() => setTab(item.value)}>{item.label}</button>)}</div><FormError message={list.error ? exchangeErrorMessage(list.error) : null} />{list.isPending && <p role="status" className="mt-5">요청 목록을 확인하고 있어요.</p>}{!list.error && list.data && <ul className="mt-5 space-y-3">{requests.map((request) => <li key={request.id} className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-navy/15 p-4"><div className="min-w-0 text-sm leading-7"><p className="font-bold break-words">교환 #{request.id} · {exchangeStatusLabels[request.status]}</p><p>{request.requesterUserId === member.id ? "내가 보낸 요청" : "받은·관련 요청"} · {request.offered.length}건 ↔ {request.requested.length}건</p></div><button type="button" className={secondaryButton} disabled={busy} onClick={() => { setId(request.id); setNotice(null); }}>상세·결과 확인</button></li>)}{!requests.length && <li className="text-sm leading-7 text-navy-soft">해당하는 교환 요청이 없어요.</li>}</ul>}</section>
  </div>;
}
