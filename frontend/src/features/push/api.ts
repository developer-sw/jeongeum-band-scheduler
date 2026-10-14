import { ApiError, apiRequest } from "@/lib/api/client";
import type { PushDevice, PushDeviceList, PushPublicKey, PushRegistration } from "./types";

const instant = (value: unknown) => typeof value === "string" && Number.isFinite(Date.parse(value));
export function validPushDevice(value: unknown): value is PushDevice {
  if (!value || typeof value !== "object") return false;
  const item = value as Partial<PushDevice>;
  return typeof item.id === "string" && /^[1-9]\d*$/.test(item.id)
    && typeof item.deviceName === "string" && typeof item.active === "boolean" && typeof item.currentDevice === "boolean"
    && typeof item.bindingVersion === "string" && Boolean(item.bindingVersion)
    && instant(item.createdAt) && instant(item.updatedAt);
}
async function data<T>(request: ReturnType<typeof apiRequest<T>>) {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "알림 설정 응답을 확인하지 못했어요.");
  return response.data;
}
export async function getPushPublicKey(signal?: AbortSignal): Promise<PushPublicKey> {
  const result = await data(apiRequest<PushPublicKey>("/api/v1/push/public-key", { signal }));
  if (!result || typeof result.enabled !== "boolean" || (result.publicKey !== null && typeof result.publicKey !== "string") || result.enabled && !result.publicKey) throw new ApiError(200, "INVALID_RESPONSE", "알림 설정을 확인하지 못했어요.");
  return result;
}
export async function getPushDevices(signal?: AbortSignal): Promise<PushDeviceList> {
  const result = await data(apiRequest<PushDeviceList>("/api/v1/push-subscriptions", { signal }));
  if (!result || !Array.isArray(result.subscriptions) || result.subscriptions.some((item) => !validPushDevice(item))) throw new ApiError(200, "INVALID_RESPONSE", "기기 목록을 확인하지 못했어요.");
  return result;
}
export async function registerPushDevice(input: PushRegistration, signal?: AbortSignal): Promise<PushDevice> {
  const result = await data(apiRequest<PushDevice>("/api/v1/push-subscriptions", { method: "POST", body: input, signal }));
  if (!validPushDevice(result) || !result.active || !result.currentDevice) throw new ApiError(200, "INVALID_RESPONSE", "이 기기의 연결 결과를 확인하지 못했어요.");
  return result;
}
export const deletePushDevice = (id: string, signal?: AbortSignal) => apiRequest(`/api/v1/push-subscriptions/${encodeURIComponent(id)}`, { method: "DELETE", signal });
