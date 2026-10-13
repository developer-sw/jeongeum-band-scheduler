import { ApiError, apiRequest } from "@/lib/api/client";
import type { Notice, NoticeIntent, NoticeList } from "./types";

const instant = (value: unknown) => typeof value === "string" && Number.isFinite(Date.parse(value));
export function validNotice(value: unknown): value is Notice {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<Notice>;
  return typeof item.id === "string" && Boolean(item.id) && typeof item.title === "string" && typeof item.body === "string"
    && typeof item.pinned === "boolean" && typeof item.authorUserId === "string" && Boolean(item.authorUserId)
    && instant(item.createdAt) && instant(item.updatedAt) && Number.isInteger(item.version) && item.version! >= 0 && typeof item.deleted === "boolean";
}
async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "공지 응답을 확인하지 못했어요.");
  return response.data;
}
export async function getNotices(cursor?: string, signal?: AbortSignal): Promise<NoticeList> {
  const params = new URLSearchParams({ limit: "20" }); if (cursor) params.set("cursor", cursor);
  const result = await data(apiRequest<NoticeList>(`/api/v1/notices?${params}`, { signal }));
  if (!result || !instant(result.serverTime) || !Array.isArray(result.notices) || result.notices.some((item) => !validNotice(item))
    || result.nextCursor !== null && (typeof result.nextCursor !== "string" || !result.nextCursor)) throw new ApiError(200, "INVALID_RESPONSE", "공지 목록을 확인하지 못했어요.");
  return result;
}
export async function getNotice(id: string, signal?: AbortSignal): Promise<Notice> {
  const result = await data(apiRequest<Notice>(`/api/v1/notices/${encodeURIComponent(id)}`, { signal }));
  if (!validNotice(result) || result.id !== id) throw new ApiError(200, "INVALID_RESPONSE", "공지 내용을 확인하지 못했어요.");
  return result;
}
export async function submitNoticeIntent(intent: NoticeIntent, key: string, signal?: AbortSignal): Promise<Notice> {
  const path = intent.kind === "CREATE" ? "/api/v1/notices" : `/api/v1/notices/${encodeURIComponent(intent.id)}` as const;
  const result = await data(apiRequest<Notice>(path, { method: intent.kind === "CREATE" ? "POST" : intent.kind === "UPDATE" ? "PATCH" : "DELETE", body: intent.input, headers: { "Idempotency-Key": key }, signal }));
  if (!validNotice(result) || (intent.kind !== "CREATE" && result.id !== intent.id) || (intent.kind === "DELETE" && !result.deleted)) {
    throw new ApiError(200, "INVALID_RESPONSE", "공지 처리 결과를 확인하지 못했어요.");
  }
  return result;
}
