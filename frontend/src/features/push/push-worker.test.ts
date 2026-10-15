import { readFileSync } from "node:fs";
import { createContext, runInContext } from "node:vm";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PushBinding } from "./types";

const script = readFileSync(new URL("../../../public/push-sw.js", import.meta.url), "utf8");
interface WorkerEvent { waitUntil?: (promise: Promise<unknown>) => void; data?: unknown; ports?: { postMessage: (value: unknown) => void }[]; source?: { url: string }; notification?: unknown }
interface ShownNotification { title: string; body: string; tag: string; data: { targetPath: string; binding: PushBinding | null }; close: () => void; closed: boolean }
function worker() {
  const storage = new Map<string, unknown>();
  let transactions = Promise.resolve();
  const listeners = new Map<string, (event: WorkerEvent) => void>();
  const shown: ShownNotification[] = [];
  const initialFocus = vi.fn().mockResolvedValue({}); const focus = vi.fn().mockResolvedValue({}); const openedFocus = vi.fn().mockResolvedValue({});
  const postMessage = vi.fn(), openedMessage = vi.fn();
  const openWindow = vi.fn().mockImplementation(async (url: string) => ({ url, focus: openedFocus, postMessage: openedMessage }));
  const navigate = vi.fn().mockImplementation(async (url: string) => ({ url, focus, postMessage }));
  const db = { close: vi.fn(), createObjectStore: vi.fn(), transaction: () => {
    const gate = transactions;
    let finish!: () => void; transactions = new Promise<void>((resolve) => { finish = resolve; });
    let pending = 0, completed = false;
    function schedule(task: () => void) {
      pending++;
      void gate.then(() => queueMicrotask(() => {
        if (completed) return;
        task(); pending--;
        queueMicrotask(() => {
          if (completed || pending) return;
          completed = true; transaction.oncomplete?.(); finish();
        });
      }));
    }
    const transaction: { oncomplete?: () => void; onabort?: () => void; abort: () => void; objectStore: () => unknown } = {
      abort: () => { completed = true; transaction.onabort?.(); finish(); },
      objectStore: () => ({ get: (key: string) => {
        const request: { result?: unknown; onsuccess?: () => void } = {};
        schedule(() => { request.result = structuredClone(storage.get(key)); request.onsuccess?.(); }); return request;
      }, put: (value: unknown, key: string) => schedule(() => storage.set(key, structuredClone(value))),
      delete: (key: string) => schedule(() => storage.delete(key)) }),
    };
    return transaction;
  } };
  const self = { location: { origin: "https://jeongeum.example" }, addEventListener: (name: string, callback: (event: WorkerEvent) => void) => listeners.set(name, callback), skipWaiting: vi.fn().mockResolvedValue(undefined), clients: { claim: vi.fn().mockResolvedValue(undefined), matchAll: vi.fn().mockResolvedValue([{ url: "https://jeongeum.example/", focus: initialFocus, navigate, postMessage }]), openWindow }, registration: { getNotifications: vi.fn().mockImplementation(async () => shown.filter((notification) => !notification.closed)), showNotification: vi.fn().mockImplementation(async (title: string, options: Omit<ShownNotification, "title" | "close" | "closed">) => { const notification: ShownNotification = { ...options, title, closed: false, close: () => { notification.closed = true; } }; shown.push(notification); }) } };
  runInContext(script, createContext({ self, indexedDB: { open: () => { const request: { result: typeof db; onsuccess?: () => void } = { result: db }; queueMicrotask(() => request.onsuccess?.()); return request; } }, URL, Promise, Date, setTimeout, clearTimeout }));
  async function emit(type: string, event: WorkerEvent) { const pending: Promise<unknown>[] = []; listeners.get(type)?.({ ...event, waitUntil: (promise) => pending.push(promise) }); await Promise.all(pending); }
  async function message(data: unknown, origin = "https://jeongeum.example/") { const reply = vi.fn(); await emit("message", { source: { url: origin }, ports: [{ postMessage: reply }], data }); return reply; }
  async function set(value: PushBinding | null, origin = "https://jeongeum.example/", preservePendingClick = false) { return message({ type: "SET_PUSH_BINDING", binding: value, preservePendingClick }, origin); }
  async function take(origin = "https://jeongeum.example/") { return (await message({ type: "TAKE_PUSH_CLICK" }, origin)).mock.calls[0]?.[0]; }
  async function diagnostics() { return (await message({ type: "READ_PUSH_DIAGNOSTICS" })).mock.calls[0]?.[0]?.diagnostics; }
  async function push(payload: unknown) { await emit("push", { data: { json: () => payload } }); return shown.at(-1)!; }
  return { set, push, shown, emit, message, take, diagnostics, storage, postMessage, openedMessage, openWindow, navigate, initialFocus, focus, openedFocus, self, listeners, binding: () => storage.get("current") ?? null };
}
const binding = { subscriptionId: "71", bindingVersion: "2" };
const payload = { ...binding, eventKey: "synthetic-event-41", title: "가상 합주 알림", body: "합주가 1시간 뒤 시작돼요.", targetPath: "/reservations?reservationId=41" };
afterEach(() => vi.useRealTimers());
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
  it("restores an existing window before navigating and then focuses the returned window", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification });
    expect(fixture.initialFocus).toHaveBeenCalledOnce();
    expect(fixture.navigate).toHaveBeenCalledWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.focus).toHaveBeenCalledOnce();
    expect(fixture.initialFocus.mock.invocationCallOrder[0]).toBeLessThan(fixture.navigate.mock.invocationCallOrder[0]);
    expect(fixture.navigate.mock.invocationCallOrder[0]).toBeLessThan(fixture.focus.mock.invocationCallOrder[0]);
    expect(fixture.openWindow).not.toHaveBeenCalled();
  });
  it("still navigates when restoring an existing window rejects", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.initialFocus.mockRejectedValue(new TypeError("Synthetic suspended window"));
    await expect(fixture.emit("notificationclick", { notification })).resolves.toBeUndefined();
    expect(fixture.initialFocus).toHaveBeenCalledOnce();
    expect(fixture.navigate).toHaveBeenCalledWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.focus).toHaveBeenCalledOnce();
    expect(fixture.openWindow).not.toHaveBeenCalled();
  });
  it.each(["rejects", "returns null"])("opens the exact notification destination when navigation %s", async (failure) => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    if (failure === "rejects") fixture.navigate.mockRejectedValue(new TypeError("Synthetic inactive document"));
    else fixture.navigate.mockResolvedValue(null);
    await expect(fixture.emit("notificationclick", { notification })).resolves.toBeUndefined();
    expect(fixture.initialFocus).toHaveBeenCalledOnce();
    expect(fixture.openWindow).toHaveBeenCalledExactlyOnceWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.openedFocus).toHaveBeenCalledOnce();
  });
  it("falls back when the navigated window cannot be focused", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.focus.mockRejectedValue(new TypeError("Synthetic inactive document"));
    await expect(fixture.emit("notificationclick", { notification })).resolves.toBeUndefined();
    expect(fixture.openWindow).toHaveBeenCalledExactlyOnceWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.openedFocus).toHaveBeenCalledOnce();
  });
  it("continues to another same-origin window when the first one cannot navigate", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.navigate.mockRejectedValue(new TypeError("Synthetic inactive document"));
    const nextInitialFocus = vi.fn().mockResolvedValue({}); const nextFocus = vi.fn().mockResolvedValue({});
    const nextNavigate = vi.fn().mockResolvedValue({ focus: nextFocus });
    fixture.self.clients.matchAll.mockResolvedValue([
      { url: "https://jeongeum.example/notifications/settings", focus: fixture.initialFocus, navigate: fixture.navigate },
      { url: "https://jeongeum.example/", focus: nextInitialFocus, navigate: nextNavigate },
    ]);
    await expect(fixture.emit("notificationclick", { notification })).resolves.toBeUndefined();
    expect(fixture.navigate).toHaveBeenCalledOnce();
    expect(nextNavigate).toHaveBeenCalledWith(`https://jeongeum.example${payload.targetPath}`);
    expect(nextInitialFocus.mock.invocationCallOrder[0]).toBeLessThan(nextNavigate.mock.invocationCallOrder[0]);
    expect(nextFocus).toHaveBeenCalledOnce();
    expect(fixture.openWindow).not.toHaveBeenCalled();
  });
  it("ignores windows from another origin", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    const outsideFocus = vi.fn(); const outsideNavigate = vi.fn();
    fixture.self.clients.matchAll.mockResolvedValue([
      { url: "https://outside.example/", focus: outsideFocus, navigate: outsideNavigate },
      { url: "https://jeongeum.example/", focus: fixture.initialFocus, navigate: fixture.navigate },
    ]);
    await fixture.emit("notificationclick", { notification });
    expect(outsideFocus).not.toHaveBeenCalled(); expect(outsideNavigate).not.toHaveBeenCalled();
    expect(fixture.navigate).toHaveBeenCalledWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.openWindow).not.toHaveBeenCalled();
  });
  it("opens and focuses a new window when no existing window is available", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.self.clients.matchAll.mockResolvedValue([]);
    await fixture.emit("notificationclick", { notification });
    expect(fixture.navigate).not.toHaveBeenCalled();
    expect(fixture.openWindow).toHaveBeenCalledExactlyOnceWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.openedFocus).toHaveBeenCalledOnce();
  });
  it("accepts an openWindow result without an accessible window client", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.self.clients.matchAll.mockResolvedValue([]); fixture.openWindow.mockResolvedValue(null);
    await expect(fixture.emit("notificationclick", { notification })).resolves.toBeUndefined();
    expect(fixture.openWindow).toHaveBeenCalledExactlyOnceWith(`https://jeongeum.example${payload.targetPath}`);
    expect(fixture.openedFocus).not.toHaveBeenCalled();
  });
  it("retains account and URL boundaries when opening a fallback window", async () => {
    const fixture = worker(); await fixture.set(binding); fixture.navigate.mockRejectedValue(new TypeError("Synthetic inactive document"));
    const notification = await fixture.push(payload);
    notification.data.targetPath = "https://outside.example/";
    await fixture.emit("notificationclick", { notification });
    expect(fixture.openWindow).toHaveBeenLastCalledWith("https://jeongeum.example/notifications");
    const oldAccountNotification = await fixture.push(payload);
    await fixture.set({ subscriptionId: "72", bindingVersion: "3" });
    await fixture.emit("notificationclick", { notification: oldAccountNotification });
    expect(fixture.openWindow).toHaveBeenLastCalledWith("https://jeongeum.example/notifications");
    const loggedOutNotification = await fixture.push({ ...payload, subscriptionId: "72", bindingVersion: "3" });
    await fixture.set(null);
    await fixture.emit("notificationclick", { notification: loggedOutNotification });
    expect(fixture.openWindow).toHaveBeenLastCalledWith("https://jeongeum.example/notifications");
    expect(fixture.openWindow).toHaveBeenCalledTimes(3);
  });
  it("recovers a clicked destination once even when native navigation resolves at the previous URL", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.navigate.mockResolvedValue({ url: "https://jeongeum.example/notifications/settings", focus: fixture.focus });
    await fixture.emit("notificationclick", { notification });
    expect(fixture.storage.get("pending-click")).toMatchObject({ targetPath: payload.targetPath, binding });
    expect(fixture.postMessage).toHaveBeenCalledExactlyOnceWith({ type: "JEONGEUM_PUSH_CLICK" });
    expect(await fixture.diagnostics()).toMatchObject({ workerVersion: "push-click-recovery-v2", targetMatched: false, lastRecovery: "PENDING" });
    const results = await Promise.all([fixture.take(), fixture.take()]);
    expect(results.filter(result => result.path === payload.targetPath)).toHaveLength(1);
    expect(results.filter(result => result.path === null)).toHaveLength(1);
    expect(fixture.storage.has("pending-click")).toBe(false);
    expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: "CONSUMED" });
  });
  it("preserves a click through temporary authentication reconciliation and waits for its binding", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification });
    await fixture.set(null, undefined, true);
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    expect(fixture.storage.has("pending-click")).toBe(true);
    expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: "WAITING_FOR_BINDING" });
    await fixture.set(binding);
    expect(await fixture.take()).toEqual({ ok: true, path: payload.targetPath });
  });
  it("clears a pending click on logout or when a different account binding is assigned", async () => {
    const fixture = worker(); await fixture.set(binding); const first = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification: first }); await fixture.set(null);
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: "CLEARED" });
    await fixture.set(binding); const second = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification: second });
    await fixture.set({ subscriptionId: "72", bindingVersion: "3" });
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    expect(fixture.storage.has("pending-click")).toBe(false);
  });
  it("atomically rejects a saved click if its current binding no longer matches", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification });
    fixture.storage.set("current", { subscriptionId: "72", bindingVersion: "3" });
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    expect(fixture.storage.has("pending-click")).toBe(false);
    expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: "ACCOUNT_CHANGED" });
  });
  it.each([{ elapsed: 119999, path: payload.targetPath, outcome: "CONSUMED" }, { elapsed: 120000, path: null, outcome: "EXPIRED" }])(
    "expires click recovery at 120 seconds (elapsed $elapsed)", async ({ elapsed, path, outcome }) => {
      vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"] });
      const start = new Date("2026-10-15T07:00:00Z"); vi.setSystemTime(start);
      const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
      await fixture.emit("notificationclick", { notification });
      vi.setSystemTime(new Date(start.getTime() + elapsed));
      expect(await fixture.take()).toEqual({ ok: true, path });
      expect(fixture.storage.has("pending-click")).toBe(false);
      expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: outcome });
    });
  it("never creates recovery navigation merely because a push arrived", async () => {
    const fixture = worker(); await fixture.set(binding); await fixture.push(payload);
    expect(fixture.storage.has("pending-click")).toBe(false);
    expect(fixture.navigate).not.toHaveBeenCalled(); expect(fixture.openWindow).not.toHaveBeenCalled();
    expect(fixture.postMessage).not.toHaveBeenCalled();
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    expect(await fixture.diagnostics()).toEqual({ workerVersion: "push-click-recovery-v2", clickReceivedAt: null,
      lastNativeResult: null, lastRecovery: null, windowCount: null, targetMatched: null });
  });
  it("rejects another origin's attempts to consume clicks or read diagnostics", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification });
    for (const type of ["READ_PUSH_BINDING", "TAKE_PUSH_CLICK", "READ_PUSH_DIAGNOSTICS"]) {
      expect(await fixture.message({ type }, "https://outside.example/")).not.toHaveBeenCalled();
    }
    expect(await fixture.take()).toEqual({ ok: true, path: payload.targetPath });
  });
  it("allows only a generic inbox recovery without a bound account", async () => {
    const fixture = worker(); const notification = await fixture.push(payload);
    await fixture.emit("notificationclick", { notification });
    expect(await fixture.take()).toEqual({ ok: true, path: "/notifications" });
    fixture.storage.set("pending-click", { targetPath: "/notices/41", binding: null, createdAt: Date.now() });
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: "INVALID" });
  });
  it.each(["https://outside.example/", "/admin/members", "/notices/41#private", "/reservations?reservationId=41&other=1"])(
    "rejects a corrupted recovery destination %s", async (targetPath) => {
      const fixture = worker(); await fixture.set(binding);
      fixture.storage.set("pending-click", { targetPath, binding, createdAt: Date.now() });
      expect(await fixture.take()).toEqual({ ok: true, path: null });
      expect(fixture.storage.has("pending-click")).toBe(false);
    });
  it("keeps diagnostics private and lets recovery finish before hung native operations time out", async () => {
    vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"] });
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.initialFocus.mockImplementation(() => new Promise(() => {}));
    fixture.navigate.mockImplementation(() => new Promise(() => {}));
    fixture.openWindow.mockImplementation(() => new Promise(() => {}));
    const click = fixture.emit("notificationclick", { notification });
    await vi.advanceTimersByTimeAsync(0);
    expect(fixture.initialFocus).toHaveBeenCalledOnce();
    expect(await fixture.message({ type: "READ_PUSH_BINDING" })).toHaveBeenCalledWith({ ok: true, binding });
    expect(await fixture.take()).toEqual({ ok: true, path: payload.targetPath });
    const during = await fixture.diagnostics();
    expect(during).toMatchObject({ workerVersion: "push-click-recovery-v2", lastRecovery: "CONSUMED", windowCount: 1 });
    expect(new Date(during.clickReceivedAt).getTime()).not.toBeNaN();
    await vi.advanceTimersByTimeAsync(4500); await click;
    const final = await fixture.diagnostics();
    expect(final).toMatchObject({ lastNativeResult: "OPEN_WINDOW_TIMEOUT", lastRecovery: "CONSUMED" });
    expect(Object.keys(final).sort()).toEqual(["clickReceivedAt", "lastNativeResult", "lastRecovery", "targetMatched", "windowCount", "workerVersion"]);
    expect(JSON.stringify(final)).not.toContain(payload.title); expect(JSON.stringify(final)).not.toContain(payload.body);
    expect(JSON.stringify(final)).not.toContain(payload.targetPath); expect(JSON.stringify(final)).not.toContain("subscriptionId");
    await fixture.set(null); expect(fixture.binding()).toBeNull();
  });
  it("does not disclose corrupted diagnostic strings or additional stored fields", async () => {
    const fixture = worker();
    fixture.storage.set("diagnostics", { clickReceivedAt: "private account", lastNativeResult: "https://private.endpoint/",
      lastRecovery: "private-session", windowCount: -1, targetMatched: "private", targetPath: "/notices/41", binding });
    expect(await fixture.diagnostics()).toEqual({ workerVersion: "push-click-recovery-v2", clickReceivedAt: null,
      lastNativeResult: null, lastRecovery: null, windowCount: null, targetMatched: null });
  });
  it("reconciles ownership immediately while two native windows hang without a delayed binding clear", async () => {
    vi.useFakeTimers({ toFake: ["Date", "setTimeout", "clearTimeout"] });
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.initialFocus.mockImplementation(() => new Promise(() => {}));
    fixture.navigate.mockImplementation(() => new Promise(() => {}));
    const secondFocus = vi.fn().mockImplementation(() => new Promise(() => {}));
    const secondNavigate = vi.fn().mockImplementation(() => new Promise(() => {}));
    fixture.self.clients.matchAll.mockResolvedValue([
      { url: "https://jeongeum.example/notifications/settings", focus: fixture.initialFocus, navigate: fixture.navigate, postMessage: fixture.postMessage },
      { url: "https://jeongeum.example/", focus: secondFocus, navigate: secondNavigate, postMessage: vi.fn() },
    ]);
    const click = fixture.emit("notificationclick", { notification });
    await vi.advanceTimersByTimeAsync(0);
    expect(fixture.initialFocus).toHaveBeenCalledOnce();
    let reconciled = false;
    const reconciliation = (async () => {
      expect(await fixture.message({ type: "READ_PUSH_BINDING" })).toHaveBeenCalledWith({ ok: true, binding });
      expect(await fixture.set(null, undefined, true)).toHaveBeenCalledWith({ ok: true, binding: null });
      expect(fixture.binding()).toBeNull();
      // The page has verified the current device through the normal authenticated API.
      expect(await fixture.set(binding)).toHaveBeenCalledWith({ ok: true, binding });
      reconciled = true;
    })();
    await vi.advanceTimersByTimeAsync(0);
    expect(reconciled).toBe(true); await reconciliation;
    expect(await fixture.take()).toEqual({ ok: true, path: payload.targetPath });
    expect(await fixture.take()).toEqual({ ok: true, path: null });
    await vi.advanceTimersByTimeAsync(6000); await click;
    expect(secondFocus).toHaveBeenCalledOnce(); expect(secondNavigate).toHaveBeenCalledOnce();
    expect(fixture.binding()).toEqual(binding);
    expect(await fixture.message({ type: "READ_PUSH_BINDING" })).toHaveBeenCalledWith({ ok: true, binding });
    expect(await fixture.diagnostics()).toMatchObject({ lastRecovery: "CONSUMED", windowCount: 2 });
  });
  it("keeps click recovery available when focusing the newly opened window rejects", async () => {
    const fixture = worker(); await fixture.set(binding); const notification = await fixture.push(payload);
    fixture.self.clients.matchAll.mockResolvedValue([]);
    fixture.openedFocus.mockRejectedValue(new TypeError("Synthetic restored window"));
    await expect(fixture.emit("notificationclick", { notification })).resolves.toBeUndefined();
    expect(await fixture.take()).toEqual({ ok: true, path: payload.targetPath });
    expect(await fixture.diagnostics()).toMatchObject({ lastNativeResult: "OPENED_FOCUS_REJECTED", targetMatched: true });
  });
  it("does not register caching/fetch handlers, store private HTML, or replay offline writes", () => { const fixture = worker(); expect([...fixture.listeners.keys()]).toEqual(["install", "activate", "message", "push", "notificationclick"]); expect(script).not.toMatch(/caches\.|fetch\(/); });
});
