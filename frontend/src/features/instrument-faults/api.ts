import { ApiError, apiRequest } from "@/lib/api/client";
import type { Instrument } from "@/features/reservations/types";
import { instrumentCodes, type FaultIntent, type InstrumentFault, type InstrumentFaultList } from "./types";

async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "악기 상태 응답을 확인하지 못했어요.");
  return response.data;
}
export const getInstruments = (signal?: AbortSignal) => data(apiRequest<Instrument[]>("/api/v1/instruments", { signal }));
export async function getInstrumentFaults(filter: { instrumentCode?: string; beforeId?: string; limit?: number } = {}, signal?: AbortSignal): Promise<InstrumentFaultList> {
  const params = new URLSearchParams();
  if (filter.instrumentCode) params.set("instrumentCode", filter.instrumentCode);
  if (filter.beforeId) params.set("beforeId", filter.beforeId);
  params.set("limit", String(filter.limit ?? 20));
  const result = await data(apiRequest<InstrumentFaultList>(`/api/v1/instrument-faults?${params}`, { signal }));
  if (!result || !Array.isArray(result.incidents) || !Number.isFinite(Date.parse(result.serverTime))
    || result.nextBeforeId !== null && (typeof result.nextBeforeId !== "string" || !result.nextBeforeId)
    || result.incidents.some((incident) => !validFault(incident))) throw new ApiError(200, "INVALID_RESPONSE", "고장 이력을 확인하지 못했어요.");
  return result;
}
// A malformed success can follow a committed write. Keep its original body/key for explicit retry.
export function validFault(value: unknown): value is InstrumentFault {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<InstrumentFault>;
  return typeof item.id === "string" && Boolean(item.id) && instrumentCodes.includes(item.instrumentCode!)
    && typeof item.instrumentName === "string" && typeof item.description === "string"
    && ["OPEN", "RESOLVED"].includes(String(item.status)) && ["ADMIN", "LEGACY"].includes(String(item.origin))
    && Number.isInteger(item.version) && item.version! >= 0 && Number.isInteger(item.assetVersion) && item.assetVersion! >= 0
    && typeof item.reportedAt === "string" && Number.isFinite(Date.parse(item.reportedAt))
    && (item.reportedByUserId === null || typeof item.reportedByUserId === "string")
    && (item.resolvedByUserId === null || typeof item.resolvedByUserId === "string")
    && (item.resolutionNote === null || typeof item.resolutionNote === "string")
    && (item.status === "OPEN" ? item.resolvedAt === null : typeof item.resolvedAt === "string" && Number.isFinite(Date.parse(item.resolvedAt)));
}
export async function submitFaultIntent(intent: FaultIntent, key: string, signal?: AbortSignal): Promise<InstrumentFault> {
  const path = intent.kind === "REPORT" ? "/api/v1/instrument-faults" : `/api/v1/instrument-faults/${encodeURIComponent(intent.incidentId)}/resolve` as const;
  const result = await data(apiRequest<InstrumentFault>(path, { method: "POST", body: intent.input, headers: { "Idempotency-Key": key }, signal }));
  if (!validFault(result) || (intent.kind === "REPORT" ? result.instrumentCode !== intent.input.instrumentCode : result.id !== intent.incidentId || result.status !== "RESOLVED")) {
    throw new ApiError(200, "INVALID_RESPONSE", "고장 처리 결과를 확인하지 못했어요.");
  }
  return result;
}
