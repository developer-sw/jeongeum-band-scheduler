import type { InstrumentCode } from "@/features/reservations/types";
import type { CreateExchangeInput, ExchangeCandidate, ExchangeResponse } from "./types";

export const exchangeStatusLabels = { PENDING: "승인 대기", COMPLETED: "교환 완료", REJECTED: "거절됨", CANCELED: "요청 취소", EXPIRED: "만료됨", INVALIDATED: "무효화됨" } as const;
export const candidateMinutes = (item: ExchangeCandidate) => Math.round((Date.parse(item.endsAt) - Date.parse(item.startsAt)) / 60_000);
export const groupMinutes = (items: ExchangeCandidate[]) => items.reduce((total, item) => total + candidateMinutes(item), 0);
export const orderedCandidates = (items: ExchangeCandidate[]) => [...items].sort((a, b) => a.startsAt.localeCompare(b.startsAt) || a.id.localeCompare(b.id));

export function groupError(items: ExchangeCandidate[], now: number, offered: boolean): string | null {
  if (items.length < 1 || items.length > 2) return "양쪽 예약을 한 건 또는 두 건 선택해 주세요.";
  if (new Set(items.map((item) => item.id)).size !== items.length) return "같은 예약을 중복으로 선택할 수 없어요.";
  if (items.some((item) => item.status !== "CONFIRMED" || Date.parse(item.startsAt) <= now)) return "양쪽 모두 아직 시작하지 않은 확정 예약을 선택해 주세요.";
  if (offered && items.some((item) => !item.canChange)) return "내 쪽 모든 예약의 변경 권한이 있어야 교환을 요청할 수 있어요.";
  if (items.length === 2) {
    const [first, second] = orderedCandidates(items);
    if (first.date !== second.date || !first.bundleKey || first.bundleKey !== second.bundleKey || candidateMinutes(first) !== 60 || candidateMinutes(second) !== 60 || Date.parse(first.endsAt) !== Date.parse(second.startsAt)) return "두 건은 같은 날짜의 연속 60분, 같은 개인·팀과 같은 최초 예약자여야 해요.";
    if (first.subjectType === "PERSONAL" && new Set(items.flatMap((item) => item.instrumentCodes)).size > 2) return "묶음 개인 예약의 악기는 최대 두 종류를 유지할 수 있어요.";
  }
  return null;
}
export function createExchangeInput(offered: ExchangeCandidate[], requested: ExchangeCandidate[], now: number): CreateExchangeInput | string {
  const invalid = groupError(offered, now, true) ?? groupError(requested, now, false);
  if (invalid) return invalid;
  if (offered.some((item) => requested.some((other) => other.id === item.id))) return "한 예약을 양쪽에 넣을 수 없어요.";
  if (offered.length === 2 && requested.length === 2) return "두 건끼리의 교환은 지원하지 않아요. 연속 60분 두 건과 90분 한 건을 선택해 주세요.";
  if (offered.length === 2 && candidateMinutes(requested[0]) !== 90 || requested.length === 2 && candidateMinutes(offered[0]) !== 90) return "연속 60분 두 건의 상대는 90분 한 건이어야 해요.";
  for (const [source, target] of [[offered, requested], [requested, offered]]) {
    if (source.length === 1 && source[0].subjectType === "PERSONAL" && source[0].instrumentCodes.length > 1 && target.length === 1 && candidateMinutes(target[0]) === 60) return "두 악기 90분 예약은 악기를 유지하며 60분 한 건과 교환할 수 없어요. 연속 두 블록을 선택해 주세요.";
  }
  const items = (group: ExchangeCandidate[]) => orderedCandidates(group).map(({ id, version }) => ({ reservationId: id, expectedVersion: version }));
  return { offered: items(offered), requested: items(requested) };
}
export function pendingExchange(request: ExchangeResponse, now: number) { return request.status === "PENDING" && now < Date.parse(request.expiresAt); }
export function allocationInput(request: ExchangeResponse, sourceId: string, order: InstrumentCode[], memberId: string, now: number) {
  const source = [...request.offered, ...request.requested].find((item) => item.id === sourceId);
  if (!pendingExchange(request, now) || !request.canAllocateReservationIds.includes(sourceId) || source?.subjectType !== "PERSONAL" || source.ownerUserId !== memberId || source.instrumentCodes.length !== 2 || order.length !== 2 || new Set(order).size !== 2 || !source.instrumentCodes.every((code) => order.includes(code))) return "해당 개인 예약자만 기존 두 악기의 순서를 선택할 수 있어요.";
  return { expectedVersion: request.version, reservationId: sourceId, instrumentCodesOrder: [...order] };
}
