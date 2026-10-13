import { ApiError, apiRequest } from "@/lib/api/client";
import type { Notification, NotificationList, ReadAllResult, ReadIntent } from "./types";
const instant = (value: unknown) => typeof value === "string" && Number.isFinite(Date.parse(value));
const count = (value: unknown) => typeof value === "number" && Number.isInteger(value) && value >= 0;
const fraction = (value: string) => (value.match(/\.(\d+)(?:Z|[+-]\d{2}:\d{2})$/i)?.[1] ?? "").padEnd(9, "0");
export function validNotification(value: unknown): value is Notification {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<Notification>;
  return typeof item.id === "string" && Boolean(item.id) && typeof item.eventType === "string" && Boolean(item.eventType)
    && typeof item.title === "string" && typeof item.body === "string" && (item.targetPath === null || typeof item.targetPath === "string") && instant(item.createdAt) && (item.readAt === null || instant(item.readAt));
}
async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> { const response = await request; if (!response) throw new ApiError(200, "INVALID_RESPONSE", "알림 응답을 확인하지 못했어요."); return response.data; }
export async function getNotifications(filter: { beforeId?: string; unreadOnly?: boolean } = {}, signal?: AbortSignal): Promise<NotificationList> {
  const params = new URLSearchParams({ limit: "20", unreadOnly: String(filter.unreadOnly ?? false) }); if (filter.beforeId) params.set("beforeId", filter.beforeId);
  const result = await data(apiRequest<NotificationList>(`/api/v1/notifications?${params}`, { signal }));
  if (!result || !instant(result.serverTime) || !count(result.unreadCount) || !Array.isArray(result.notifications) || result.notifications.some((item) => !validNotification(item))
    || result.nextBeforeId !== null && (typeof result.nextBeforeId !== "string" || !result.nextBeforeId)) throw new ApiError(200, "INVALID_RESPONSE", "알림 목록을 확인하지 못했어요.");
  return result;
}
export async function submitReadIntent(intent: ReadIntent, signal?: AbortSignal): Promise<Notification | ReadAllResult> {
  if (intent.kind === "SINGLE") {
    const result = await data(apiRequest<Notification>(`/api/v1/notifications/${encodeURIComponent(intent.id)}/read`, { method: "PATCH", signal }));
    if (!validNotification(result) || result.id !== intent.id || result.readAt === null) throw new ApiError(200, "INVALID_RESPONSE", "알림 읽음 결과를 확인하지 못했어요.");
    return result;
  }
  const result = await data(apiRequest<ReadAllResult>("/api/v1/notifications/read-all", { method: "POST", body: { through: intent.through }, signal }));
  if (!result || !instant(result.through) || Date.parse(result.through) !== Date.parse(intent.through) || fraction(result.through) !== fraction(intent.through) || !count(result.readCount) || !count(result.unreadCount)) throw new ApiError(200, "INVALID_RESPONSE", "알림 읽음 결과를 확인하지 못했어요.");
  return result;
}
