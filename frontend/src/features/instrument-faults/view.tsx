import { primaryButton } from "@/components/auth-ui";
import type { Instrument } from "@/features/reservations/types";
import type { FaultEditorSelection, InstrumentFault } from "./types";

export function faultTime(value: string | null) {
  if (!value || !Number.isFinite(Date.parse(value))) return "기록 없음";
  return new Intl.DateTimeFormat("ko-KR", { timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit", hour12: false }).format(new Date(value));
}
export function InstrumentStatusCard({ instrument, incident, disabled, onSelect }: { instrument: Instrument; incident?: InstrumentFault; disabled: boolean; onSelect: (selection: FaultEditorSelection) => void }) {
  const faulty = instrument.status === "FAULTY";
  return <li className={`min-w-0 rounded-2xl border p-5 ${faulty ? "border-yellow bg-yellow/10" : "border-line bg-white"}`}>
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="text-lg font-bold">{instrument.name}</h3><span className={`rounded-lg px-3 py-1 text-sm font-bold ${faulty ? "bg-yellow/20" : "bg-paper"}`}>{faulty ? "고장 · 개인 새 예약 제한" : "정상"}</span></div>
    {faulty ? <><p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{incident?.description ?? "열린 고장 이력을 다시 확인해 주세요."}</p>{incident && <p className="mt-2 text-xs leading-6 text-navy-soft">{incident.origin === "LEGACY" ? "기존 고장 상태 확인" : "등록"} · {faultTime(incident.reportedAt)}</p>}<p className="mt-3 text-sm leading-7 text-navy-soft">기존 예약은 유지돼요. 팀 합주는 고장 안내와 함께 허용돼요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={disabled || !incident} onClick={() => incident && onSelect({ kind: "RESOLVE", instrument, incident })} aria-label={`${instrument.name} 복구 처리`}>복구 처리</button></>
      : <><p className="mt-3 text-sm leading-7 text-navy-soft">악기에 문제가 생기면 사유와 함께 등록해 주세요.</p><button type="button" className={`${primaryButton} mt-4`} disabled={disabled} onClick={() => onSelect({ kind: "REPORT", instrument })} aria-label={`${instrument.name} 고장 등록`}>고장 등록</button></>}
  </li>;
}
export function FaultHistoryCard({ incident }: { incident: InstrumentFault }) {
  return <li className="min-w-0 rounded-2xl border border-line bg-white p-5">
    <div className="flex flex-wrap items-center justify-between gap-3"><h3 className="font-bold">{incident.instrumentName} <span className="text-sm font-normal text-navy-soft">고장 #{incident.id}</span></h3><span className="rounded-lg bg-paper px-3 py-1 text-sm font-semibold">{incident.status === "OPEN" ? "고장 · 복구 대기" : "복구 완료"}</span></div>
    <p className="mt-3 whitespace-pre-wrap break-words text-sm leading-7">{incident.description}</p>
    <dl className="mt-4 space-y-2 text-sm leading-6 text-navy-soft"><div><dt className="inline font-semibold">{incident.origin === "LEGACY" ? "기존 상태 확인 시각" : "등록 시각"}: </dt><dd className="inline">{faultTime(incident.reportedAt)}</dd></div><div><dt className="inline font-semibold">등록자: </dt><dd className="inline break-all">{incident.reportedByUserId ? `관리 계정 #${incident.reportedByUserId}` : "이전 등록자 확인 불가"}</dd></div>{incident.status === "RESOLVED" && <><div><dt className="inline font-semibold">복구 시각: </dt><dd className="inline">{faultTime(incident.resolvedAt)}</dd></div><div><dt className="inline font-semibold">복구 처리자: </dt><dd className="inline break-all">{incident.resolvedByUserId ? `관리 계정 #${incident.resolvedByUserId}` : "기록 없음"}</dd></div><div><dt className="inline font-semibold">복구 메모: </dt><dd className="inline whitespace-pre-wrap break-words">{incident.resolutionNote || "메모 없음"}</dd></div></>}</dl>
    {incident.origin === "LEGACY" && <p className="mt-3 text-xs leading-6 text-navy-soft">이전에 고장으로 표시된 상태를 연결한 이력이에요. 원래 등록 시각과 등록자는 확인할 수 없어요.</p>}
  </li>;
}
