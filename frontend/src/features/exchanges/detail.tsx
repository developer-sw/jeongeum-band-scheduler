"use client";

import Link from "next/link";
import { useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import type { Member } from "@/features/auth/types";
import { blockLabel, dateLabel } from "@/features/reservations/time";
import type { BlockCode, InstrumentCode, ReservationDetail } from "@/features/reservations/types";
import { instrumentLabels, instrumentNames, subjectLabel } from "@/features/reservations/view";
import { allocationInput, exchangeStatusLabels, pendingExchange } from "./selection";
import type { ExchangeIntent, ExchangePlacement, ExchangeResponse } from "./types";

const blockTimes: Record<BlockCode, string> = { B01: "12:00~13:00", B02: "13:00~14:00", B03: "14:00~15:00", B04: "15:00~16:00", B05: "16:00~17:00", B06: "17:00~18:00", B07: "18:00~19:30", B08: "19:30~21:00", B09: "21:00~22:30", B10: "22:30~24:00" };
const instantLabel = (value: string) => new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", month: "long", day: "numeric", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).format(new Date(value));
const stateReasons: Record<string, string> = { RESERVATION_CHANGED: "관련 예약이 변경됐어요.", RESERVATION_CANCELED: "관련 예약이 취소됐어요.", SOURCE_CHANGED: "원본 예약의 상태가 바뀌었어요.", REQUESTER_PERMISSION_LOST: "요청자의 현재 권한이 바뀌었어요.", EXCHANGE_COMPLETED: "다른 교환에서 원본 예약이 처리됐어요.", RESERVATION_STARTED: "가장 이른 예약의 시작 시각이 지났어요.", EARLIEST_START_REACHED: "가장 이른 예약의 시작 시각이 지났어요.", SUBJECT_INACTIVE: "예약 주체가 비활성 상태로 바뀌었어요." };

function OriginalGroup({ title, items }: { title: string; items: ReservationDetail[] }) {
  const minutes = items.reduce((sum, item) => sum + (Date.parse(item.endsAt) - Date.parse(item.startsAt)) / 60_000, 0);
  return <div className="min-w-0 rounded-xl bg-paper p-4 text-sm leading-7"><h3 className="font-bold">{title} · 총 {minutes}분</h3><ul className="mt-2 space-y-2">{items.map((item) => <li key={item.id}><p>{dateLabel(item.date)} · {blockLabel(item)}</p><p>{subjectLabel(item)} · {item.subjectType === "TEAM" ? "전체 독점" : instrumentNames(item.instrumentCodes)} · 예약 #{item.id}</p>{item.quotaExempt && <p>원래 고정 60분 면제 · 이동한 예약에 면제를 넘기지 않아요.</p>}</li>)}</ul></div>;
}
function Placement({ item }: { item: ExchangePlacement }) {
  return <li className="min-w-0 rounded-xl border border-navy/15 p-4 text-sm leading-7"><p className="font-bold">{item.side === "REQUESTER" ? "요청자 쪽 도착 예약" : "상대 쪽 도착 예약"}</p><p>{dateLabel(item.date)} · {blockTimes[item.blockCode]}</p><p>{item.subjectType === "TEAM" ? `${item.teamName ?? "팀 합주"} · 전체 독점` : `개인 연습 · ${item.allocationRequired ? "예약자의 악기 순서 선택 필요" : instrumentNames(item.instrumentCodes)}`}</p><p>이 예약의 60분 한도 소비: {item.quotaMinutes}분</p>{item.teamFaultWarnings.length > 0 && <p className="text-red-800">팀 합주 고장 안내: {instrumentNames(item.teamFaultWarnings)}</p>}</li>;
}
function AllocationForm({ request, source, member, now, disabled, onAction }: { request: ExchangeResponse; source: ReservationDetail; member: Member; now: number; disabled: boolean; onAction: (intent: ExchangeIntent) => void }) {
  const targetSide = request.offered.some((item) => item.id === source.id) ? "REQUESTER" : "COUNTERPART";
  const targets = request.preview.filter((item) => item.side === targetSide).sort((a, b) => a.date.localeCompare(b.date) || a.blockCode.localeCompare(b.blockCode));
  const savedFirst = targets[0]?.allocationRequired === false && targets[0]?.instrumentCodes.length === 1 && source.instrumentCodes.includes(targets[0].instrumentCodes[0]) ? targets[0].instrumentCodes[0] : source.instrumentCodes[0];
  const [first, setFirst] = useState<InstrumentCode>(savedFirst);
  const [error, setError] = useState<string | null>(null);
  const second = source.instrumentCodes.find((code) => code !== first)!;
  const canAllocate = pendingExchange(request, now) && request.canAllocateReservationIds.includes(source.id) && source.ownerUserId === member.id;
  if (!canAllocate) return <p className="rounded-xl bg-paper p-4 text-sm leading-7">예약 #{source.id}의 두 악기 배분은 해당 개인 예약자만 저장할 수 있어요. 전체 관리자도 대신 순서를 선택할 수 없어요.</p>;
  return <form method="post" className="space-y-3 rounded-xl border border-yellow bg-yellow/10 p-4 text-sm leading-7" onSubmit={(event) => { event.preventDefault(); if (disabled) return; const input = allocationInput(request, source.id, [first, second], member.id, now); if (typeof input === "string") setError(input); else onAction({ kind: "ALLOCATE", id: request.id, input }); }}><h3 className="font-bold">내 두 악기의 60분 배분 · 예약 #{source.id}</h3><Field id={`allocation-${source.id}`} label={`${targets[0] ? `${dateLabel(targets[0].date)} ${blockTimes[targets[0].blockCode]}` : "첫 60분"} 악기`}><select id={`allocation-${source.id}`} className={inputClass} value={first} disabled={disabled} onChange={(event) => { setFirst(event.target.value as InstrumentCode); setError(null); }}>{source.instrumentCodes.map((code) => <option key={code} value={code}>{instrumentLabels[code]}</option>)}</select></Field><p>{targets[1] ? `${dateLabel(targets[1].date)} ${blockTimes[targets[1].blockCode]}` : "두 번째 60분"}에는 {instrumentLabels[second]}를 배정해요.</p><p>기존 두 악기를 하나씩 유지하고 순서만 선택해요. 저장한 최신 배분을 승인에 사용해요.</p><FormError message={error} /><button type="submit" className={secondaryButton} disabled={disabled}>악기 순서 저장</button></form>;
}
export function ExchangeDetail({ request, member, now, disabled, onAction }: { request: ExchangeResponse; member: Member; now: number; disabled: boolean; onAction: (intent: ExchangeIntent) => void }) {
  const [confirmation, setConfirmation] = useState<{ kind: "APPROVE" | "REJECT" | "CANCEL"; version: number } | null>(null);
  const pending = pendingExchange(request, now);
  const missingAllocation = request.preview.some((item) => item.allocationRequired);
  const actions = [
    { kind: "APPROVE" as const, label: "교환 승인", allowed: request.canApprove && !missingAllocation },
    { kind: "REJECT" as const, label: "교환 거절", allowed: request.canReject },
    { kind: "CANCEL" as const, label: "요청 취소", allowed: request.canCancel },
  ];
  const confirm = confirmation && confirmation.version === request.version && pending && actions.some((item) => item.kind === confirmation.kind && item.allowed) ? confirmation : null;
  return <div className="space-y-5">
    <div className="text-sm leading-7"><h2 className="text-xl font-bold">교환 요청 #{request.id} · {exchangeStatusLabels[request.status]}</h2><p>요청 {instantLabel(request.createdAt)} · 만료 {instantLabel(request.expiresAt)} (서울 기준)</p>{request.processedAt && <p>처리 {instantLabel(request.processedAt)}</p>}{request.status === "PENDING" && !pending && <p role="status" className="text-red-800">예약 시작 시각에 도달했어요. 승인할 수 없으며 최신 요청 상태를 다시 확인해 주세요.</p>}{request.stateReason && <p>{stateReasons[request.stateReason] ?? "예약·권한·시작 시각이 바뀌어 요청 상태가 갱신됐어요."}</p>}</div>
    <div className="grid gap-4 sm:grid-cols-2"><OriginalGroup title="요청자 쪽 원본" items={request.offered} /><OriginalGroup title="상대 쪽 원본" items={request.requested} /></div>
    <section className="space-y-3" aria-label="교환 결과 미리보기"><h3 className="font-bold">양쪽 도착 시간·악기 확인</h3><ul className="grid gap-3 sm:grid-cols-2">{request.preview.map((item, index) => <Placement key={`${item.side}:${item.date}:${item.blockCode}:${index}`} item={item} />)}</ul><p className="text-sm leading-7 text-navy-soft">각자의 주체·최초 예약자를 유지해요. 이동한 60분에 고정 면제를 적용하지 않으며 다음 주 반복 배정은 유지해요. 한도는 주체·날짜별 최종 합계로 검사하고 실패하면 양쪽 예약·점유·한도를 모두 유지해요.</p></section>
    {pending && request.allocationReservationIds.map((id) => { const source = [...request.offered, ...request.requested].find((item) => item.id === id); return source ? <AllocationForm key={`${id}:${request.version}`} request={request} source={source} member={member} now={now} disabled={disabled} onAction={onAction} /> : null; })}
    {pending && missingAllocation && <p role="status" className="text-sm leading-7">예약자의 두 악기 순서를 저장하면 최신 미리보기를 확인하고 승인할 수 있어요.</p>}
    {pending && <p className="text-sm leading-7 text-navy-soft">대기 중 원래 예약은 그대로예요. 제3자 예약·현재 고장·한도·권한은 승인할 때 다시 검사해요. 일반 팀원은 관련 요청을 볼 수 있어도 다른 팀원이 만든 예약을 승인할 수 없어요.</p>}
    {pending && !actions.some((item) => item.allowed) && <p className="text-sm leading-7 text-navy-soft">현재 계정·요청 상태에서는 승인·거절·취소 권한이 없어요. 서버의 최신 권한을 기준으로 표시해요.</p>}
    {pending && <div className="flex flex-wrap gap-3">{actions.filter((item) => item.allowed).map((item) => <button type="button" key={item.kind} className={secondaryButton} disabled={disabled} onClick={() => setConfirmation({ kind: item.kind, version: request.version })}>{item.label} 내용 확인</button>)}</div>}
    {pending && confirmation && !confirm && <p role="status" className="text-sm leading-7 text-red-800">요청의 배분·버전·상태가 바뀌었어요. 최신 내용을 다시 확인해 주세요.</p>}
    {confirm && <section className="rounded-2xl border border-yellow bg-yellow/10 p-5 text-sm leading-7" role="alert"><h3 className="font-bold">{actions.find((item) => item.kind === confirm.kind)!.label} 최종 확인</h3><p>{confirm.kind === "APPROVE" ? "위의 최신 시간·악기·길이 변화를 확인했어요. 성공하면 양측 전체 예약을 함께 교환하고 원본을 이력으로 보존해요." : "교환 요청만 종료하며 양측 원래 예약은 그대로 유지해요."}</p><div className="mt-3 flex flex-wrap gap-3"><button type="button" className={primaryButton} disabled={disabled} onClick={() => { setConfirmation(null); onAction({ kind: confirm.kind, id: request.id, input: { expectedVersion: request.version } }); }}>{actions.find((item) => item.kind === confirm.kind)!.label} 확정</button><button type="button" className={secondaryButton} disabled={disabled} onClick={() => setConfirmation(null)}>다시 검토</button></div></section>}
    {request.status === "COMPLETED" && <section aria-label="교환 완료 예약" className="rounded-2xl bg-paper p-5"><h3 className="font-bold">양측 교환이 모두 완료됐어요</h3><ul className="mt-3 space-y-3 text-sm leading-7">{request.reservations.map((item) => <li key={item.id}><p>{subjectLabel(item)} · {dateLabel(item.date)} · {blockLabel(item)} · {item.subjectType === "TEAM" ? "전체 독점" : instrumentNames(item.instrumentCodes)}</p>{item.isMine || item.canChange || item.canCancel ? <Link className="underline underline-offset-4" href={`/reservations?reservationId=${encodeURIComponent(item.id)}`}>후속 예약 #{item.id} 상세·이력 →</Link> : <p>상대 쪽 후속 예약 #{item.id}</p>}</li>)}</ul></section>}
  </div>;
}
