import { ApiError } from "@/lib/api/client";
import { deletePushDevice, registerPushDevice } from "./api";
import type { PushBinding, PushDevice } from "./types";

export type PushCapability = "CHECKING" | "INSECURE" | "INSTALL_REQUIRED" | "UNSUPPORTED" | "DENIED" | "AVAILABLE";
export function pushCapability(): PushCapability {
  if (typeof window === "undefined") return "CHECKING";
  if (!window.isSecureContext) return "INSECURE";
  const ios = /iPad|iPhone|iPod/.test(navigator.userAgent) || navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1;
  const standalone = window.matchMedia("(display-mode: standalone)").matches || (navigator as Navigator & { standalone?: boolean }).standalone === true;
  if (ios && !standalone) return "INSTALL_REQUIRED";
  if (!("serviceWorker" in navigator) || !("PushManager" in window) || !("Notification" in window)) return "UNSUPPORTED";
  if (Notification.permission === "denied") return "DENIED";
  return "AVAILABLE";
}
export function subscribeCapability(callback: () => void) {
  window.addEventListener("focus", callback); window.addEventListener("pageshow", callback); window.addEventListener("online", callback); window.addEventListener("offline", callback);
  const media = window.matchMedia("(display-mode: standalone)"); media.addEventListener("change", callback);
  return () => { window.removeEventListener("focus", callback); window.removeEventListener("pageshow", callback); window.removeEventListener("online", callback); window.removeEventListener("offline", callback); media.removeEventListener("change", callback); };
}
export function applicationServerKey(value: string): Uint8Array<ArrayBuffer> {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("Invalid public key");
  const bytes = Uint8Array.from(atob(value.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - value.length % 4) % 4)), (char) => char.charCodeAt(0));
  if (bytes.length !== 65 || bytes[0] !== 4) throw new Error("Invalid public key");
  return bytes;
}
export async function pushWorker(register = false): Promise<ServiceWorkerRegistration | undefined> {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return undefined;
  const existing = register ? await navigator.serviceWorker.register("/push-sw.js", { scope: "/", updateViaCache: "none" }) : await navigator.serviceWorker.getRegistration("/");
  if (!existing) return undefined;
  if (register) {
    await existing.update();
    const worker = existing.installing ?? existing.waiting ?? existing.active;
    if (!worker) throw new Error("Push worker unavailable");
    if (worker.state !== "activated") await new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => { worker.removeEventListener("statechange", changed); reject(new Error("Push worker timeout")); }, 10000);
      const changed = () => { if (worker.state === "activated") { clearTimeout(timer); worker.removeEventListener("statechange", changed); resolve(); } else if (worker.state === "redundant") { clearTimeout(timer); worker.removeEventListener("statechange", changed); reject(new Error("Push worker unavailable")); } };
      worker.addEventListener("statechange", changed); changed();
    });
  }
  const active = existing.active;
  if (!active || new URL(active.scriptURL).pathname !== "/push-sw.js" || new URL(active.scriptURL).origin !== window.location.origin) return undefined;
  return existing;
}
async function workerMessage(registration: ServiceWorkerRegistration, message: Record<string, unknown>) {
  const worker = registration.active;
  if (!worker) throw new Error("Push worker unavailable");
  return await new Promise<Record<string, unknown>>((resolve, reject) => {
    const channel = new MessageChannel();
    const timer = setTimeout(() => { channel.port1.close(); reject(new Error("Push binding timeout")); }, 5000);
    channel.port1.onmessage = (event: MessageEvent<Record<string, unknown>>) => {
      clearTimeout(timer); channel.port1.close();
      if (event.data?.ok !== true) reject(new Error("Push worker response unavailable")); else resolve(event.data);
    };
    worker.postMessage(message, [channel.port2]);
  });
}
export async function workerBinding(registration: ServiceWorkerRegistration, binding: PushBinding | null | "READ", preservePendingClick = false) {
  const reply = await workerMessage(registration, { type: binding === "READ" ? "READ_PUSH_BINDING" : "SET_PUSH_BINDING", binding: binding === "READ" ? undefined : binding, ...(preservePendingClick ? { preservePendingClick: true } : {}) });
  return reply.binding as PushBinding | null;
}
export function allowedPushClickPath(value: unknown): string | null {
  return typeof value === "string" && value.trim() === value && !/[\\\r\n#]/.test(value) && (value === "/notifications" || /^\/notices\/[1-9]\d*$/.test(value) || /^\/reservations\?reservationId=[1-9]\d*$/.test(value) || /^\/exchanges\?exchangeId=[1-9]\d*$/.test(value)) ? value : null;
}
export async function takePendingPushClick(registration: ServiceWorkerRegistration) {
  const reply = await workerMessage(registration, { type: "TAKE_PUSH_CLICK" });
  if (reply.path === null) return null;
  const path = allowedPushClickPath(reply.path);
  if (!path) throw new Error("Invalid Push click destination");
  return path;
}
export interface PushClickDiagnostics {
  workerVersion: string;
  clickReceivedAt: string | null;
  lastNativeResult: string | null;
  lastRecovery: string | null;
  windowCount: number | null;
  targetMatched: boolean | null;
}
export async function readPushClickDiagnostics(): Promise<PushClickDiagnostics | null> {
  const registration = await pushWorker();
  if (!registration) return null;
  const reply = await workerMessage(registration, { type: "READ_PUSH_DIAGNOSTICS" });
  const data = reply.diagnostics as Partial<PushClickDiagnostics> | undefined;
  if (!data || typeof data.workerVersion !== "string" || data.workerVersion.trim() !== data.workerVersion || !/^[a-z0-9-]{1,80}$/.test(data.workerVersion)
    || !(data.clickReceivedAt === null || typeof data.clickReceivedAt === "string" && Number.isFinite(Date.parse(data.clickReceivedAt)))
    || ![data.lastNativeResult, data.lastRecovery].every((value) => value === null || typeof value === "string" && value.trim() === value && /^[A-Z0-9_]{1,80}$/.test(value))
    || !(data.windowCount === null || typeof data.windowCount === "number" && Number.isSafeInteger(data.windowCount) && data.windowCount >= 0)
    || !(data.targetMatched === null || typeof data.targetMatched === "boolean")) throw new Error("Invalid Push diagnostics");
  return { workerVersion: data.workerVersion, clickReceivedAt: data.clickReceivedAt, lastNativeResult: data.lastNativeResult, lastRecovery: data.lastRecovery, windowCount: data.windowCount, targetMatched: data.targetMatched } as PushClickDiagnostics;
}
export async function clearCurrentPushBinding() {
  const registration = await pushWorker();
  if (registration) await workerBinding(registration, null);
}
/** Browser storage/message failures must not prevent the server from ending a session. */
export async function cleanupCurrentPushDevice(): Promise<{ browserCleanupComplete: boolean }> {
  let complete = true;
  try { await clearCurrentPushBinding(); } catch { complete = false; }
  try { const registration = await pushWorker(); const subscription = await registration?.pushManager.getSubscription(); await subscription?.unsubscribe(); } catch { complete = false; }
  return { browserCleanupComplete: complete };
}
/** Server confirmation precedes the UI's off state. Other devices never touch this browser. */
export async function disablePushDevice(device: PushDevice, signal?: AbortSignal): Promise<{ browserCleanupComplete: boolean }> {
  await deletePushDevice(device.id, signal);
  if (signal?.aborted) throw new DOMException("Canceled", "AbortError");
  if (!device.currentDevice) return { browserCleanupComplete: true };
  return cleanupCurrentPushDevice();
}
/** Call directly from a user's click: requestPermission runs before the first await. */
export async function enableCurrentDevice(publicKey: string, deviceName: string, signal?: AbortSignal): Promise<PushDevice> {
  if (pushCapability() !== "AVAILABLE") throw new DOMException("Push is unavailable", "NotAllowedError");
  if (!navigator.onLine) throw new ApiError(0, "OFFLINE", "");
  const key = applicationServerKey(publicKey);
  const permission = Notification.permission === "granted" ? "granted" : await Notification.requestPermission();
  if (permission !== "granted") throw new DOMException("Notification permission not granted", "NotAllowedError");
  const registration = await pushWorker(true);
  if (!registration) throw new Error("Push worker unavailable");
  // Keep stale ownership unbound until the authenticated server confirms this account.
  await workerBinding(registration, null);
  const subscription = await registration.pushManager.getSubscription() ?? await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  const serialized = subscription.toJSON();
  if (!serialized.endpoint || !serialized.keys?.p256dh || !serialized.keys?.auth) throw new Error("Push subscription unavailable");
  const device = await registerPushDevice({ endpoint: serialized.endpoint, keys: { p256dh: serialized.keys.p256dh, auth: serialized.keys.auth }, deviceName: deviceName.trim() || "내 기기" }, signal);
  if (signal?.aborted) throw new DOMException("Canceled", "AbortError");
  await workerBinding(registration, { subscriptionId: device.id, bindingVersion: device.bindingVersion });
  if (signal?.aborted) { await workerBinding(registration, null); throw new DOMException("Canceled", "AbortError"); }
  return device;
}
