"use client";

import { useRef, useState } from "react";
import { Field, FormError, inputClass, primaryButton, secondaryButton } from "@/components/auth-ui";
import { blockLabel, dateLabel } from "@/features/reservations/time";
import { instrumentNames, subjectLabel } from "@/features/reservations/view";
import { candidateMinutes, createExchangeInput, groupMinutes, orderedCandidates } from "./selection";
import type { CreateExchangeInput, ExchangeCandidate } from "./types";

export function ExchangeGroup({ title, items }: { title: string; items: ExchangeCandidate[] }) {
  return <div className="min-w-0 rounded-xl bg-paper p-4 text-sm leading-7"><h3 className="font-bold">{title} · 총 {groupMinutes(items)}분</h3><ul className="mt-2 space-y-2">{orderedCandidates(items).map((item) => <li key={item.id}><p>{dateLabel(item.date)} · {blockLabel(item)} ({candidateMinutes(item)}분)</p><p>{subjectLabel(item)} · {item.subjectType === "TEAM" ? "합주실 전체 독점" : instrumentNames(item.instrumentCodes)} · 예약 #{item.id}</p>{item.quotaExempt && <p>원래 관리자 배정의 60분 면제</p>}</li>)}</ul></div>;
}
export function ExchangeArrival({ title, source, target }: { title: string; source: ExchangeCandidate[]; target: ExchangeCandidate[] }) {
  if (!source.length) return null;
  const instruments = [...new Set(source.flatMap((item) => item.instrumentCodes))];
  const requiresAllocation = source.length === 1 && instruments.length === 2 && target.length === 2;
  return <div className="min-w-0 rounded-xl bg-white p-4 text-sm leading-7"><h4 className="font-bold">{title} · 총 {groupMinutes(target)}분</h4><p>{subjectLabel(source[0])} · {source[0].subjectType === "TEAM" ? "합주실 전체 독점" : instrumentNames(instruments)}</p><ul>{orderedCandidates(target).map((item) => <li key={item.id}>{dateLabel(item.date)} · {blockLabel(item)} ({candidateMinutes(item)}분) · {source[0].subjectType === "TEAM" ? "전체 독점" : requiresAllocation ? "개인 예약자가 악기 하나씩 순서 선택" : instrumentNames(instruments)}</li>)}</ul><p>이동 후 60분 한도 소비: {target.reduce((sum, item) => sum + (candidateMinutes(item) === 60 ? 60 : 0), 0)}분</p></div>;
}
export function CreateExchangeForm({ candidates, now, disabled, initialId = "", onCreate }: { candidates: ExchangeCandidate[]; now: number; disabled: boolean; initialId?: string; onCreate: (input: CreateExchangeInput) => void }) {
  const [offeredIds, setOfferedIds] = useState<string[]>(initialId ? [initialId] : []);
  const [requestedIds, setRequestedIds] = useState<string[]>([]);
  const [offeredDate, setOfferedDate] = useState(""); const [requestedDate, setRequestedDate] = useState("");
  const [review, setReview] = useState<CreateExchangeInput | null>(null);
  const [error, setError] = useState<string | null>(null); const title = useRef<HTMLHeadingElement>(null);
  const offered = candidates.filter((item) => offeredIds.includes(item.id));
  const requested = candidates.filter((item) => requestedIds.includes(item.id));
  const selection = createExchangeInput(offered, requested, now);
  const missingSelection = offeredIds.some((id) => !offered.some((item) => item.id === id)) || requestedIds.some((id) => !requested.some((item) => item.id === id));
  const currentReview = review && typeof selection !== "string" && JSON.stringify(selection) === JSON.stringify(review);
  function toggle(id: string, side: "offered" | "requested", checked: boolean) {
    const ids = side === "offered" ? offeredIds : requestedIds;
    const set = side === "offered" ? setOfferedIds : setRequestedIds;
    set(checked ? [...ids, id] : ids.filter((item) => item !== id)); setReview(null); setError(null);
  }
  function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault(); if (disabled || missingSelection) return;
    if (typeof selection === "string") { setError(selection); return; }
    if (!currentReview) { setReview(selection); setError(null); requestAnimationFrame(() => title.current?.focus()); return; }
    onCreate(selection);
  }
  function choices(side: "offered" | "requested", label: string, filterDate: string, setDate: (date: string) => void) {
    const selected = side === "offered" ? offeredIds : requestedIds;
    const others = side === "offered" ? requestedIds : offeredIds;
    const available = candidates.filter((item) => (side !== "offered" || item.canChange) && Date.parse(item.startsAt) > now);
    const dates = [...new Set(available.map((item) => item.date))].sort();
    return <fieldset disabled={disabled} className="min-w-0 rounded-2xl border border-navy/15 p-4"><legend className="px-2 font-bold">{label}</legend><Field id={`exchange-${side}-date`} label="예약 날짜 필터"><select id={`exchange-${side}-date`} className={inputClass} value={filterDate} onChange={(event) => setDate(event.target.value)}><option value="">모든 예약 날짜</option>{dates.map((date) => <option key={date} value={date}>{dateLabel(date)}</option>)}</select></Field><p className="mt-3 text-xs leading-6 text-navy-soft">최대 두 건 선택 · 다른 날짜로 필터해도 선택은 유지돼요.</p><ul className="mt-3 max-h-80 space-y-2 overflow-y-auto">{available.filter((item) => !filterDate || item.date === filterDate || selected.includes(item.id)).map((item) => <li key={item.id}><label className={`flex min-h-11 items-start gap-3 rounded-xl border p-3 text-sm ${selected.includes(item.id) ? "border-yellow bg-yellow/10" : "border-navy/15"}`}><input type="checkbox" className="mt-1 shrink-0" checked={selected.includes(item.id)} disabled={others.includes(item.id) || !selected.includes(item.id) && selected.length >= 2} onChange={(event) => toggle(item.id, side, event.target.checked)} /><span className="min-w-0 break-words">{dateLabel(item.date)} · {blockLabel(item)} · {candidateMinutes(item)}분<br />{subjectLabel(item)} · {item.subjectType === "TEAM" ? "전체 독점" : instrumentNames(item.instrumentCodes)}<br /><span className="text-xs text-navy-soft">예약 #{item.id}{item.quotaExempt && " · 원래 60분 면제"}</span></span></label></li>)}</ul>{!available.length && <p className="mt-3 text-sm leading-7 text-navy-soft">선택 가능한 시작 전 예약이 없어요.</p>}</fieldset>;
  }
  return <form onSubmit={submit} method="post" className="space-y-5" aria-busy={disabled}>
    <p className="text-sm leading-7 text-navy-soft">내 쪽은 현재 변경 권한이 있는 예약을 선택해요. 상대는 개인·정규조·추가조 모두 선택할 수 있어요.</p>
    <div className="grid gap-4 lg:grid-cols-2">{choices("offered", "내 쪽 예약", offeredDate, setOfferedDate)}{choices("requested", "상대 쪽 예약", requestedDate, setRequestedDate)}</div>
    {(offeredIds.some((id) => !offered.some((item) => item.id === id)) || requestedIds.some((id) => !requested.some((item) => item.id === id))) && <p role="status" className="text-sm leading-7 text-red-800">선택했던 예약이 더 이상 교환 후보가 아니에요. 선택을 초기화하고 다시 확인해 주세요.</p>}
    <div className="grid gap-4 sm:grid-cols-2"><ExchangeGroup title="내 쪽 현재 예약" items={offered} /><ExchangeGroup title="상대 쪽 현재 예약" items={requested} /></div>
    {currentReview && <section aria-labelledby="exchange-create-review" className="rounded-2xl border border-yellow bg-yellow/10 p-5 text-sm leading-7"><h3 id="exchange-create-review" ref={title} tabIndex={-1} className="font-bold">교환 요청 전 확인</h3><p>내 쪽 이용 시간: {groupMinutes(offered)}분 → {groupMinutes(requested)}분</p><p>상대 쪽 이용 시간: {groupMinutes(requested)}분 → {groupMinutes(offered)}분</p><div className="my-3 grid gap-3 sm:grid-cols-2"><ExchangeArrival title="내 쪽 도착 시간" source={offered} target={requested} /><ExchangeArrival title="상대 쪽 도착 시간" source={requested} target={offered} /></div><p>시간·날짜만 서로 받고 각자의 개인·팀 주체, 최초 예약자, 악기는 유지해요. 팀 예약은 결과에서도 합주실 전체를 독점해요.</p><p>개인의 서로 다른 두 악기는 90분 전체를 점유해요. 두 악기 90분이 두 60분으로 나뉘면 해당 개인 예약자가 요청 상세에서 순서를 저장해야 해요.</p><p>이동한 60분은 일반 한도에 포함하며 원래 고정 배정의 면제를 상대에게 넘기지 않아요. 같은 개인·팀의 당일 다른 예약도 합산해 승인 시 서버가 검사해요.</p><p>이 회차만 교환하며 다음 주 반복 배정은 유지해요. 가장 이른 원본 시작 시각에 요청이 만료돼요.</p></section>}
    <p className="text-sm leading-7 text-navy-soft">요청 대기 중 원래 예약·점유·한도를 유지해요. 요청만으로 새 시간을 확보하지 않으며 승인 시 권한·충돌·고장·한도를 다시 확인해요.</p>
    <FormError message={error ?? (typeof selection === "string" ? selection : null)} />
    <div className="flex flex-wrap gap-3"><button type="submit" className={primaryButton} disabled={disabled || missingSelection || typeof selection === "string"}>{currentReview ? "교환 요청 보내기" : "교환 내용 확인"}</button><button type="button" className={secondaryButton} disabled={disabled} onClick={() => { setOfferedIds([]); setRequestedIds([]); setReview(null); setError(null); }}>선택 초기화</button>{review && <button type="button" className={secondaryButton} disabled={disabled} onClick={() => setReview(null)}>비교 다시 확인</button>}</div>
  </form>;
}
