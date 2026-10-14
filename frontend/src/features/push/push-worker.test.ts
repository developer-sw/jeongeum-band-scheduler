import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { describe, expect, it, vi } from "vitest";
import type { PushBinding } from "./types";

const script = readFileSync(new URL("../../../public/push-sw.js", import.meta.url), "utf8");
interface WorkerEvent { waitUntil?: (promise: Promise<unknown>) => void; data?: unknown; ports?: { postMessage: (value: unknown) => void }[]; source?: { url: string }; notification?: unknown }
interface ShownNotification { title: string; body: string; tag: string; data: { targetPath: string; binding: PushBinding | null }; close: () => void; closed: boolean }
function worker() {
  let binding: PushBinding | null = null;
  const listeners = new Map<string, (event: WorkerEvent) => void>();
  const shown: ShownNotification[] = [];
  const openWindow = vi.fn().mockResolvedValue({}); const focus = vi.fn().mockResolvedValue({}); const navigate = vi.fn().mockResolvedValue({ focus });
  const db = { close: vi.fn(), createObjectStore: vi.fn(), transaction: () => {
    const transaction: { oncomplete?: () => void; objectStore: () => unknown } = { objectStore: () => ({ get: () => { const request: { result: PushBinding | null; onsuccess?: () => void } = { result: binding }; queueMicrotask(() => request.onsuccess?.()); return request; }, put: (value: PushBinding) => { binding = value; queueMicrotask(() => transaction.oncomplete?.()); }, delete: () => { binding = null; queueMicrotask(() => transaction.oncomplete?.()); } }) };
    return transaction;
  } };
  const self = { location: { origin: "https://jeongeum.example" }, addEventListener: (name: string, callback: (event: WorkerEvent) => void) => listeners.set(name, callback), skipWaiting: vi.fn().mockResolvedValue(undefined), clients: { claim: vi.fn().mockResolvedValue(undefined), matchAll: vi.fn().mockResolvedValue([{ url: "https://jeongeum.example/", navigate }]), openWindow }, registration: { getNotifications: vi.fn().mockImplementation(async () => shown.filter((notification) => !notification.closed)), showNotification: vi.fn().mockImplementation(async (title: string, options: Omit<ShownNotification, "title" | "close" | "closed">) => { const notification: ShownNotification = { ...options, title, closed: false, close: () => { notification.closed = true; } }; shown.push(notification); }) } };
  runInContext(script, createContext({ self, indexedDB: { open: () => { const request: { result: typeof db; onsuccess?: () => void } = { result: db }; queueMicrotask(() => request.onsuccess?.()); return request; } }, URL, Promise }));
  async function emit(type: string, event: WorkerEvent) { const pending: Promise<unknown>[] = []; listeners.get(type)?.({ ...event, waitUntil: (promise) => pending.push(promise) }); await Promise.all(pending); }
  async function set(value: PushBinding | null, origin = "https://jeongeum.example/") { const reply = vi.fn(); await emit("message", { source: { url: origin }, ports: [{ postMessage: reply }], data: { type: "SET_PUSH_BINDING", binding: value } }); return reply; }
  async function push(payload: unknown) { await emit("push", { data: { json: () => payload } }); return shown.at(-1)!; }
  return { set, push, shown, emit, openWindow, navigate, focus, self, listeners, binding: () => binding };
}
const binding = { subscriptionId: "71", bindingVersion: "2" };
const payload = { ...binding, eventKey: "synthetic-event-41", title: "가상 합주 알림", body: "합주가 1시간 뒤 시작돼요.", targetPath: "/reservations?reservationId=41" };
describe("Push-only service worker lifecycle and private payload boundaries", () => {
  it("accepts a first device binding at version zero and shows only its matching personal payload", async () => {
    const fixture = worker();
    const initialBinding = { subscriptionId: "71", bindingVersion: "0" };
    const reply = await fixture.set(initialBinding);
    expect(reply).toHaveBeenCalledWith({ ok: true, binding: initialBinding });
    expect(fixture.binding()).toEqual(initialBinding);
    const initialPayload = { ...payload, ...initialBinding };
    const notification = await fixture.push(initialPayload);
    expect(notification.title).toBe(payload.title);
    expect(notification.body).toBe(payload.body);
    expect(notification.data).toEqual({ targetPath: payload.targetPath, binding: initialBinding });
    await fixture.emit("notificationclick", { notification });
    expect(fixture.navigate).toHaveBeenCalledWith(`https://jeongeum.example${payload.targetPath}`);
    await fixture.set({ ...initialBinding, bindingVersion: "1" });
    expect((await fixture.push(initialPayload)).body).toBe("로그인 후 내 알림함을 확인해 주세요.");
  });
  it("shows visible bound notifications with an event tag to collapse duplicate deliveries", async () => { const fixture = worker(); await fixture.set(binding); const first = await fixture.push(payload); const second = await fixture.push(payload); expect(first.title).toBe(payload.title); expect(first.body).toBe(payload.body); expect(first.data.targetPath).toBe(payload.targetPath); expect(first.tag).toBe(second.tag); expect(first.tag).toContain(payload.eventKey); expect(fixture.self.registration.showNotification).toHaveBeenCalledTimes(2); });
  it("uses a generic visible notification for old account/old device versions and empty payloads", async () => { const fixture = worker(); await fixture.set(binding); for (const value of [{ ...payload, bindingVersion: "1" }, { ...payload, subscriptionId: "72" }, null, {}]) { const notification = await fixture.push(value); expect(notification.title).toBe("정음 알림"); expect(notification.body).toBe("로그인 후 내 알림함을 확인해 주세요."); expect(notification.data.targetPath).toBe("/notifications"); expect(notification.body).not.toContain(payload.body); } });
  it("clears the persisted binding and displayed notifications before acknowledging logout", async () => { const fixture = worker(); await fixture.set(binding); await fixture.push(payload); const reply = await fixture.set(null); expect(fixture.binding()).toBeNull(); expect(fixture.shown[0].closed).toBe(true); expect(reply).toHaveBeenCalledWith({ ok: true, binding: null }); expect((await fixture.push(payload)).body).not.toBe(payload.body); });
  it("does not permit another origin or an invalid binding message to assign the device", async () => { const fixture = worker(); const external = await fixture.set(binding, "https://outside.example/"); expect(external).not.toHaveBeenCalled(); expect(fixture.binding()).toBeNull(); for (const value of [{ ...binding, subscriptionId: "../other" }, { ...binding, subscriptionId: "0" }, ...["-1", "00", "01", "1.0", ""].map((bindingVersion) => ({ ...binding, bindingVersion }))]) { const invalid = await fixture.set(value); expect(invalid).toHaveBeenCalledWith({ ok: false, binding: null }); expect(fixture.binding()).toBeNull(); } });
  it("sanitizes Push destinations and never opens an outside or privileged link", async () => { const fixture = worker(); await fixture.set(binding); for (const path of ["https://outside.example/", "//outside.example", "/admin/members", "/notices/41#outside", "/notices/../account", "/reservations?reservationId=41&returnUrl=https://outside.example", "/\\outside", "/exchanges?exchangeId=41\n"]) { const notification = await fixture.push({ ...payload, targetPath: path }); expect(notification.data.targetPath).toBe("/notifications"); await fixture.emit("notificationclick", { notification }); expect(fixture.navigate).toHaveBeenLastCalledWith("https://jeongeum.example/notifications"); } expect(fixture.openWindow).not.toHaveBeenCalled(); });
  it("rechecks the binding on notification click, returning a switched account to its own inbox", async () => { const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload); await fixture.set({ subscriptionId: "72", bindingVersion: "3" }); await fixture.emit("notificationclick", { notification }); expect(fixture.navigate).toHaveBeenCalledWith("https://jeongeum.example/notifications"); expect(fixture.focus).toHaveBeenCalledOnce(); });
  it("does not register caching/fetch handlers, store private HTML, or replay offline writes", () => { const fixture = worker(); expect([...fixture.listeners.keys()]).toEqual(["install", "activate", "message", "push", "notificationclick"]); expect(script).not.toMatch(/caches\.|fetch\(/); });
});
