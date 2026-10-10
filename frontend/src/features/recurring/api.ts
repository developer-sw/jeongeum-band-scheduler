import { ApiError, apiRequest } from "@/lib/api/client";
import type { Assignment, AssignmentIntent, AssignmentList, AssignmentPreview, AssignmentPreviewInput } from "./types";

async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "응답을 확인하지 못했어요.");
  return response.data;
}
export async function getAssignments(signal?: AbortSignal): Promise<AssignmentList> {
  const response = await apiRequest<Assignment[]>("/api/v1/recurring-assignments", { signal });
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "응답을 확인하지 못했어요.");
  return { assignments: response.data, serverTime: response.meta.serverTime, receivedAtMillis: Date.now() };
}
export const previewAssignment = (input: AssignmentPreviewInput, signal?: AbortSignal) => data(apiRequest<AssignmentPreview>("/api/v1/recurring-assignments/preview", { method: "POST", body: input, signal }));
export function submitAssignmentIntent(intent: AssignmentIntent, key: string, signal?: AbortSignal): Promise<Assignment> {
  const path = intent.kind === "CREATE" ? "/api/v1/recurring-assignments" : `/api/v1/recurring-assignments/${encodeURIComponent(intent.assignmentId)}${intent.kind === "END" ? "/end" : ""}` as const;
  return data(apiRequest<Assignment>(path, { method: intent.kind === "UPDATE" ? "PATCH" : "POST", body: intent.input, headers: { "Idempotency-Key": key }, signal }));
}
