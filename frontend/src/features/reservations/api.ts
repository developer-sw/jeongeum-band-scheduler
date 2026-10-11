import { ApiError, apiRequest } from "@/lib/api/client";
import type { CreateReservationInput, CancelReservationInput, ChangeReservationInput, ChangeReservationResult, ReservationDetail, ReservationIntent, Schedule, ScheduleSnapshot } from "./types";

async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "응답을 확인하지 못했어요.");
  return response.data;
}

export async function getSchedule(signal?: AbortSignal): Promise<ScheduleSnapshot> {
  const response = await apiRequest<Schedule>("/api/v1/schedule", { signal });
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "응답을 확인하지 못했어요.");
  return { ...response.data, serverTime: response.data.serverTime ?? response.meta.serverTime, receivedAtMillis: Date.now() };
}
export const getMyReservations = (from: string, to: string, signal?: AbortSignal) => data(apiRequest<ReservationDetail[]>(`/api/v1/reservations?scope=mine&from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { signal }));
export const getReservation = (id: string, signal?: AbortSignal) => data(apiRequest<ReservationDetail>(`/api/v1/reservations/${encodeURIComponent(id)}`, { signal }));
export const createReservation = (input: CreateReservationInput, key: string, signal?: AbortSignal) => data(apiRequest<ReservationDetail>("/api/v1/reservations", { method: "POST", body: input, headers: { "Idempotency-Key": key }, signal }));
export const cancelReservation = (id: string, input: CancelReservationInput, key: string, signal?: AbortSignal) => data(apiRequest<ReservationDetail>(`/api/v1/reservations/${encodeURIComponent(id)}/cancel`, { method: "POST", body: input, headers: { "Idempotency-Key": key }, signal }));
export const changeReservation = (id: string, input: ChangeReservationInput, key: string, signal?: AbortSignal) => data(apiRequest<ChangeReservationResult>(`/api/v1/reservations/${encodeURIComponent(id)}`, { method: "PATCH", body: input, headers: { "Idempotency-Key": key }, signal }));

// A malformed success can arrive after commit. Treat it as uncertain and keep
// the original body/key available, rather than clearing the request as success.
export function changedReservation(result: ChangeReservationResult, sourceId: string): ReservationDetail {
  const detail = result?.reservations?.[0];
  if (!Array.isArray(result?.replacedReservationIds) || !Array.isArray(result?.reservations) || result.reservations.length !== 1
    || !detail || typeof detail.id !== "string" || !detail.id || !Number.isInteger(detail.version) || detail.version < 0
    || typeof detail.date !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(detail.date)
    || !/^B(0[1-9]|10)$/.test(detail.blockCode) || !Number.isFinite(Date.parse(detail.startsAt)) || !Number.isFinite(Date.parse(detail.endsAt)) || Date.parse(detail.endsAt) <= Date.parse(detail.startsAt)
    || !["CONFIRMED", "CANCELED", "REPLACED"].includes(detail.status)
    || !["PERSONAL", "TEAM"].includes(detail.subjectType) || !["PERSONAL", "REGULAR", "ADDITIONAL"].includes(detail.purpose)
    || !["FUTURE", "IN_PROGRESS", "ENDED"].includes(detail.phase) || !["MANUAL", "FIXED", "CHANGE", "EXCHANGE"].includes(detail.originKind)
    || typeof detail.creatorUserId !== "string" || !detail.creatorUserId || detail.ownerUserId !== null && typeof detail.ownerUserId !== "string"
    || typeof detail.canChange !== "boolean" || typeof detail.canCancel !== "boolean" || typeof detail.isMine !== "boolean" || typeof detail.quotaExempt !== "boolean" || typeof detail.dualAssetException !== "boolean"
    || !Number.isInteger(detail.quotaMinutes) || detail.quotaMinutes < 0 || !Number.isFinite(Date.parse(detail.usableFrom)) || !Array.isArray(detail.teamFaultWarnings)
    || !Array.isArray(detail.instrumentCodes) || !detail.instrumentCodes.length || detail.instrumentCodes.some((code) => !["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"].includes(code))
    || !Array.isArray(detail.successorIds) || detail.successorIds.some((id) => typeof id !== "string" || !id)
    || !(result.replacedReservationIds.length === 0 && detail.id === sourceId
      || result.replacedReservationIds.length === 1 && result.replacedReservationIds[0] === sourceId && detail.id !== sourceId)) {
    throw new ApiError(200, "INVALID_RESPONSE", "변경 결과를 확인하지 못했어요.");
  }
  return detail;
}
export async function submitReservationIntent(intent: ReservationIntent, key: string, signal?: AbortSignal): Promise<ReservationDetail> {
  if (intent.kind === "CREATE") return createReservation(intent.input, key, signal);
  if (intent.kind === "CANCEL") return cancelReservation(intent.reservationId, intent.input, key, signal);
  return changedReservation(await changeReservation(intent.reservationId, intent.input, key, signal), intent.reservationId);
}
