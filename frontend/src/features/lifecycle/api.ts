import { ApiError, apiRequest } from "@/lib/api/client";
import type { LifecycleIntent, LifecycleResult } from "./types";

export function validLifecycleResult(value: unknown, intent: LifecycleIntent): value is LifecycleResult {
  if (!value || typeof value !== "object") return false;
  const result = value as Partial<LifecycleResult>;
  return typeof result.operationId === "string" && /^[1-9]\d*$/.test(result.operationId)
    && result.targetId === intent.id && result.status === (intent.kind === "WITHDRAW" ? "WITHDRAWN" : "DISBANDED")
    && Number.isSafeInteger(result.version) && result.version! > intent.input.expectedVersion
    && [result.canceledReservations, result.invalidatedExchanges, result.endedAssignments, result.deactivatedSubscriptions, result.revokedSessions]
      .every((count) => Number.isSafeInteger(count) && count! >= 0);
}

export async function submitLifecycleIntent(intent: LifecycleIntent, key: string, signal?: AbortSignal): Promise<LifecycleResult> {
  const path = intent.kind === "WITHDRAW"
    ? `/api/v1/users/${encodeURIComponent(intent.id)}/withdraw` as const
    : `/api/v1/teams/${encodeURIComponent(intent.id)}/disband` as const;
  const response = await apiRequest<LifecycleResult>(path, { method: "POST", body: intent.input, headers: { "Idempotency-Key": key }, signal });
  // A malformed success may follow a committed cleanup: retain the original key/body for rechecking.
  if (!validLifecycleResult(response?.data, intent)) throw new ApiError(200, "INVALID_RESPONSE", "처리 결과를 확인하지 못했어요.");
  return response!.data;
}
