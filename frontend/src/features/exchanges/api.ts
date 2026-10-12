import { ApiError, apiRequest } from "@/lib/api/client";
import type { ExchangeCandidates, ExchangeIntent, ExchangeList, ExchangeResponse } from "./types";

async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "교환 응답을 확인하지 못했어요.");
  return response.data;
}
export const getExchangeCandidates = (from: string, to: string, signal?: AbortSignal) => data(apiRequest<ExchangeCandidates>(`/api/v1/exchange-candidates?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { signal }));
export const getExchanges = (signal?: AbortSignal) => data(apiRequest<ExchangeList>("/api/v1/exchange-requests", { signal }));
export const getExchange = (id: string, signal?: AbortSignal) => data(apiRequest<ExchangeResponse>(`/api/v1/exchange-requests/${encodeURIComponent(id)}`, { signal }));

// Successful writes can have committed before a malformed/partial response arrives.
// Never discard the exact request identity until the entire result is confirmed.
export function checkedExchange(result: ExchangeResponse, intent: ExchangeIntent): ExchangeResponse {
  const instruments = ["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"];
  const validStrings = (items: unknown): items is string[] => Array.isArray(items) && items.every((item) => typeof item === "string" && !!item);
  const validInstruments = (items: unknown) => validStrings(items) && items.every((item) => instruments.includes(item));
  const validReservation = (item: ExchangeResponse["offered"][number]) => item && typeof item.id === "string" && !!item.id && Number.isInteger(item.version) && item.version >= 0 && typeof item.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(item.date) && /^B(0[1-9]|10)$/.test(item.blockCode) && Number.isFinite(Date.parse(item.startsAt)) && Number.isFinite(Date.parse(item.endsAt)) && Date.parse(item.endsAt) > Date.parse(item.startsAt) && validInstruments(item.instrumentCodes) && typeof item.creatorUserId === "string" && !!item.creatorUserId && (item.ownerUserId === null || typeof item.ownerUserId === "string") && ["PERSONAL", "TEAM"].includes(item.subjectType) && ["CONFIRMED", "CANCELED", "REPLACED"].includes(item.status) && typeof item.quotaExempt === "boolean" && typeof item.canChange === "boolean" && typeof item.canCancel === "boolean" && typeof item.isMine === "boolean" && validInstruments(item.teamFaultWarnings);
  const validPlacement = (item: ExchangeResponse["preview"][number]) => item && ["REQUESTER", "COUNTERPART"].includes(item.side) && validStrings(item.sourceReservationIds) && item.sourceReservationIds.length > 0 && ["PERSONAL", "TEAM"].includes(item.subjectType) && typeof item.date === "string" && /^\d{4}-\d{2}-\d{2}$/.test(item.date) && /^B(0[1-9]|10)$/.test(item.blockCode) && validInstruments(item.instrumentCodes) && validInstruments(item.teamFaultWarnings) && typeof item.allocationRequired === "boolean" && Number.isInteger(item.quotaMinutes) && item.quotaMinutes >= 0;
  if (!result || typeof result.id !== "string" || !result.id || !Number.isInteger(result.version) || result.version < 0
    || !["PENDING", "COMPLETED", "REJECTED", "CANCELED", "EXPIRED", "INVALIDATED"].includes(result.status)
    || typeof result.requesterUserId !== "string" || !Number.isFinite(Date.parse(result.expiresAt)) || !Number.isFinite(Date.parse(result.serverTime)) || !Number.isFinite(Date.parse(result.createdAt))
    || !Array.isArray(result.offered) || !result.offered.length || !result.offered.every(validReservation)
    || !Array.isArray(result.requested) || !result.requested.length || !result.requested.every(validReservation)
    || !Array.isArray(result.preview) || !result.preview.every(validPlacement) || !validStrings(result.allocationReservationIds) || !validStrings(result.canAllocateReservationIds)
    || typeof result.canApprove !== "boolean" || typeof result.canReject !== "boolean" || typeof result.canCancel !== "boolean"
    || !validStrings(result.replacedReservationIds) || !Array.isArray(result.reservations) || !result.reservations.every(validReservation)
    || intent.kind !== "CREATE" && result.id !== intent.id) throw new ApiError(200, "INVALID_RESPONSE", "교환 결과를 확인하지 못했어요.");
  const sourceIds = [...result.offered, ...result.requested].map((item) => item.id);
  if (new Set(sourceIds).size !== sourceIds.length || result.offered.length > 2 || result.requested.length > 2 || result.offered.length + result.requested.length > 3
    || result.preview.length !== sourceIds.length
    || intent.kind === "APPROVE" && result.status !== "COMPLETED" || intent.kind === "REJECT" && result.status !== "REJECTED" || intent.kind === "CANCEL" && result.status !== "CANCELED")
    throw new ApiError(200, "INVALID_RESPONSE", "교환 처리 상태를 확인하지 못했어요.");
  if (intent.kind === "CREATE") {
    const ids = (values: string[]) => JSON.stringify([...values].sort());
    if (ids(result.offered.map((item) => item.id)) !== ids(intent.input.offered.map((item) => item.reservationId))
      || ids(result.requested.map((item) => item.id)) !== ids(intent.input.requested.map((item) => item.reservationId)))
      throw new ApiError(200, "INVALID_RESPONSE", "다른 교환 결과를 받았어요.");
  }
  if (result.status === "COMPLETED" && (result.replacedReservationIds.length !== sourceIds.length || !sourceIds.every((id) => result.replacedReservationIds.includes(id)) || result.reservations.length !== sourceIds.length || result.reservations.some((item) => sourceIds.includes(item.id)) || new Set(result.reservations.map((item) => item.id)).size !== result.reservations.length))
    throw new ApiError(200, "INVALID_RESPONSE", "교환 전체 결과를 확인하지 못했어요.");
  return result;
}
export async function submitExchangeIntent(intent: ExchangeIntent, key: string, signal?: AbortSignal): Promise<ExchangeResponse> {
  const path: `/api/v1/${string}` = intent.kind === "CREATE" ? "/api/v1/exchange-requests" : `/api/v1/exchange-requests/${encodeURIComponent(intent.id)}/${intent.kind === "ALLOCATE" ? "instrument-allocation" : intent.kind.toLowerCase()}`;
  const result = await data(apiRequest<ExchangeResponse>(path, { method: intent.kind === "ALLOCATE" ? "PATCH" : "POST", body: intent.input, headers: { "Idempotency-Key": key }, signal }));
  return checkedExchange(result, intent);
}
