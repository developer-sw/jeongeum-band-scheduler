import { ApiError, apiRequest } from "@/lib/api/client";
import type { CreateReservationInput, CancelReservationInput, ReservationDetail, ReservationIntent, Schedule, ScheduleSnapshot } from "./types";

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
export const submitReservationIntent = (intent: ReservationIntent, key: string, signal?: AbortSignal) => intent.kind === "CREATE" ? createReservation(intent.input, key, signal) : cancelReservation(intent.reservationId, intent.input, key, signal);
