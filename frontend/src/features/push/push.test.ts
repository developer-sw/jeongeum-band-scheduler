import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { afterEach, describe, expect, it, vi } from "vitest";
import PushSettingsPage from "@/app/notifications/settings/page";
import manifest from "@/app/manifest";
import { SessionProvider } from "@/features/auth/session-provider";
import { memberQueryKey, replaceSessionCache } from "@/features/auth/session-cache";
import type { Member } from "@/features/auth/types";
import { logoutWithPushCleanup } from "@/features/auth/logout";
import { directoryScope, pruneDirectoryCache } from "@/features/directory/queries";
import { ApiError, resetApiSession } from "@/lib/api/client";
import { deletePushDevice, getPushDevices, getPushPublicKey, registerPushDevice } from "./api";
import { applicationServerKey, clearCurrentPushBinding, disablePushDevice, enableCurrentDevice, pushCapability, pushWorker, readPushClickDiagnostics, takePendingPushClick, workerBinding } from "./browser";
import { pushErrorMessage } from "./errors";
import { pushDevicesQuery, pushPublicKeyQuery, refreshPush } from "./queries";
import { CapabilityNotice, PushDeviceCard } from "./settings-view";
import type { PushBinding, PushDevice } from "./types";

vi.mock("next/navigation", () => ({ usePathname: () => "/notifications/settings" }));
const meta = { serverTime: "2026-10-14T03:00:00Z", timeZone: "Asia/Seoul" };
const publicKey = Buffer.from([4, ...Array.from({ length: 64 }, (_, i) => i)]).toString("base64url");
const device: PushDevice = { id: "71", deviceName: "가상 휴대폰", active: true, currentDevice: true, bindingVersion: "2", createdAt: meta.serverTime, updatedAt: meta.serverTime };
const member: Member = { id: "push-fixture-member", loginId: "push-fixture-member", displayName: "가상 회원", status: "ACTIVE", globalRole: "MEMBER", teamMemberships: [] };
afterEach(() => resetApiSession());
function response(data: unknown) { return Response.json({ data, meta }); }
function fakeFetch(data: unknown) { return vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : response(data)); }
function browser({ secure = true, permission = "default", ios = false, installed = false, supported = true }: { secure?: boolean; permission?: NotificationPermission; ios?: boolean; installed?: boolean; supported?: boolean } = {}) {
  let binding: PushBinding | null = { subscriptionId: "69", bindingVersion: "1" };
  const active = { state: "activated", scriptURL: "https://jeongeum.example/push-sw.js", postMessage: vi.fn((message: { type: string; binding: PushBinding | null }, ports: MessagePort[]) => { if (message.type === "SET_PUSH_BINDING") binding = message.binding; ports[0].postMessage({ ok: true, binding }); ports[0].close(); }) };
  const subscription = { toJSON: () => ({ endpoint: "https://push.example.invalid/fixture", keys: { p256dh: "fixture-public-key", auth: "fixture-auth" } }), unsubscribe: vi.fn().mockResolvedValue(true) };
  const registration = { active, update: vi.fn().mockResolvedValue(undefined), pushManager: { getSubscription: vi.fn().mockResolvedValue(null), subscribe: vi.fn().mockResolvedValue(subscription) } };
  const worker = { getRegistration: vi.fn().mockResolvedValue(registration), register: vi.fn().mockResolvedValue(registration) };
  const notification = { permission, requestPermission: vi.fn().mockResolvedValue("granted") };
  vi.stubGlobal("Notification", notification);
  vi.stubGlobal("window", { isSecureContext: secure, PushManager: supported ? class {} : undefined, Notification: notification, location: { origin: "https://jeongeum.example" }, matchMedia: () => ({ matches: installed }) });
  vi.stubGlobal("navigator", { userAgent: ios ? "iPhone" : "Fixture Browser", platform: ios ? "iPhone" : "Linux", maxTouchPoints: 0, onLine: true, ...(supported ? { serviceWorker: worker } : {}) });
  return { worker, registration, subscription, notification, binding: () => binding };
}
describe("Push device API and permission lifecycle", () => {
  it("waits for the newly installing worker when an older active worker already exists", async () => {
    const fixture = browser({ permission: "granted" });
    const installing = Object.assign(new EventTarget(), { state: "installing", scriptURL: fixture.registration.active.scriptURL });
    const listen = vi.spyOn(installing, "addEventListener");
    Object.assign(fixture.registration, { installing });
    let finished = false;
    const task = pushWorker(true).then((result) => { finished = true; return result; });
    await vi.waitFor(() => expect(listen).toHaveBeenCalledWith("statechange", expect.any(Function)));
    expect(fixture.registration.update).toHaveBeenCalledOnce();
    expect(finished).toBe(false);
    installing.state = "activated";
    Object.assign(fixture.registration, { active: installing, installing: null });
    installing.dispatchEvent(new Event("statechange"));
    expect(await task).toBe(fixture.registration);
  });
  it("does not register a server binding when the worker update check fails", async () => {
    const fixture = browser({ permission: "granted" });
    fixture.registration.update.mockRejectedValue(new TypeError("Synthetic offline update"));
    const fetchMock = fakeFetch(device); vi.stubGlobal("fetch", fetchMock);
    await expect(enableCurrentDevice(publicKey, "fixture")).rejects.toThrow("Synthetic offline update");
    expect(fetchMock).not.toHaveBeenCalled();
    expect(fixture.registration.pushManager.subscribe).not.toHaveBeenCalled();
  });
  it("preserves a click only for temporary account reconciliation, with default cleanup unchanged", async () => {
    const fixture = browser();
    const registration = fixture.registration as unknown as ServiceWorkerRegistration;
    await workerBinding(registration, null, true);
    expect(fixture.registration.active.postMessage).toHaveBeenLastCalledWith({ type: "SET_PUSH_BINDING", binding: null, preservePendingClick: true }, expect.any(Array));
    await clearCurrentPushBinding();
    expect(fixture.registration.active.postMessage).toHaveBeenLastCalledWith({ type: "SET_PUSH_BINDING", binding: null }, expect.any(Array));
  });
  it("accepts only a verified worker's internal click destination", async () => {
    const fixture = browser();
    let path: string | null = "/notices/91";
    fixture.registration.active.postMessage.mockImplementation((_message, ports) => { ports[0].postMessage({ ok: true, path }); ports[0].close(); });
    const registration = fixture.registration as unknown as ServiceWorkerRegistration;
    expect(await takePendingPushClick(registration)).toBe("/notices/91");
    for (path of ["https://outside.example/notices/1", "//outside.example", "/notices/1#extra", "/notices/1\n", "/api/v1/users", "/reservations?reservationId=1&extra=2"]) await expect(takePendingPushClick(registration)).rejects.toThrow("Invalid Push click destination");
    path = null;
    expect(await takePendingPushClick(registration)).toBeNull();
  });
  it("reads bounded non-personal click diagnostics and rejects untrusted diagnostic content", async () => {
    const fixture = browser();
    const diagnostics = { workerVersion: "push-click-recovery-v2", clickReceivedAt: meta.serverTime, lastNativeResult: "NAVIGATED", lastRecovery: "CONSUMED", windowCount: 1, targetMatched: false };
    let result: unknown = { ...diagnostics, unrelated: "not returned" };
    fixture.registration.active.postMessage.mockImplementation((_message, ports) => { ports[0].postMessage({ ok: true, diagnostics: result }); ports[0].close(); });
    expect(await readPushClickDiagnostics()).toEqual(diagnostics);
    for (result of [{ ...diagnostics, workerVersion: "invalid\nversion" }, { ...diagnostics, clickReceivedAt: "not a date" }, { ...diagnostics, lastRecovery: "untrusted text" }, { ...diagnostics, windowCount: -1 }]) await expect(readPushClickDiagnostics()).rejects.toThrow("Invalid Push diagnostics");
  });
  it("reads enabled/public key and own minimal device list without an owner parameter", async () => {
    const fetchMock = vi.fn().mockImplementation(async (path: string) => response(path.endsWith("public-key") ? { enabled: true, publicKey } : { subscriptions: [device] })); vi.stubGlobal("fetch", fetchMock);
    expect(await getPushPublicKey()).toEqual({ enabled: true, publicKey }); expect(await getPushDevices()).toEqual({ subscriptions: [device] });
    expect(fetchMock.mock.calls.map(([path]) => path)).toEqual(["/api/v1/push/public-key", "/api/v1/push-subscriptions"]); expect(fetchMock.mock.calls[1][1].cache).toBe("no-store");
  });
  it("registers browser keys with CSRF, no user ID, and deletes one own device with 204", async () => {
    const fetchMock = fakeFetch(device); vi.stubGlobal("fetch", fetchMock);
    const input = { endpoint: "https://push.example.invalid/fixture", keys: { p256dh: "fixture-public-key", auth: "fixture-auth" }, deviceName: "가상 휴대폰" };
    expect(await registerPushDevice(input)).toEqual(device); const call = fetchMock.mock.calls[1]; expect(JSON.parse(call[1].body)).toEqual(input); expect(new Headers(call[1].headers).get("X-CSRF-TOKEN")).toBe("fixture-csrf"); expect(call[1].method).toBe("POST");
    fetchMock.mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : new Response(null, { status: 204 }));
    await deletePushDevice("71"); const last = fetchMock.mock.calls.at(-1)!; expect(last[0]).toBe("/api/v1/push-subscriptions/71"); expect(last[1].method).toBe("DELETE"); expect(last[1].body).toBeUndefined();
  });
  it("rejects malformed key/list and unconfirmed device registration responses", async () => {
    for (const data of [{ enabled: true, publicKey: null }, { enabled: "true", publicKey }]) { vi.stubGlobal("fetch", fakeFetch(data)); await expect(getPushPublicKey()).rejects.toMatchObject({ code: "INVALID_RESPONSE" }); }
    for (const data of [{ subscriptions: [{ ...device, bindingVersion: null }] }, { subscriptions: [{ ...device, id: "" }] }]) { vi.stubGlobal("fetch", fakeFetch(data)); await expect(getPushDevices()).rejects.toMatchObject({ code: "INVALID_RESPONSE" }); }
    vi.stubGlobal("fetch", fakeFetch({ ...device, currentDevice: false })); await expect(registerPushDevice({ endpoint: "https://push.example.invalid/fixture", keys: { p256dh: "fixture", auth: "fixture" }, deviceName: "fixture" })).rejects.toMatchObject({ code: "INVALID_RESPONSE" });
  });
  it("distinguishes HTTP LAN, unsupported, denied and iOS installation states without requesting permission", () => {
    expect(pushCapability()).toBe("CHECKING"); const insecure = browser({ secure: false }); expect(pushCapability()).toBe("INSECURE"); expect(insecure.notification.requestPermission).not.toHaveBeenCalled();
    browser({ supported: false }); expect(pushCapability()).toBe("UNSUPPORTED"); browser({ permission: "denied" }); expect(pushCapability()).toBe("DENIED"); browser({ ios: true }); expect(pushCapability()).toBe("INSTALL_REQUIRED"); browser({ ios: true, installed: true }); expect(pushCapability()).toBe("AVAILABLE");
  });
  it("requests permission directly on click before registering a worker and binds only the confirmed own device", async () => {
    const fixture = browser(); vi.stubGlobal("fetch", fakeFetch(device));
    const task = enableCurrentDevice(publicKey, "내 휴대폰"); expect(fixture.notification.requestPermission).toHaveBeenCalledOnce(); expect(fixture.worker.register).not.toHaveBeenCalled();
    expect(await task).toEqual(device); expect(fixture.worker.register).toHaveBeenCalledWith("/push-sw.js", { scope: "/", updateViaCache: "none" }); expect(fixture.registration.pushManager.subscribe).toHaveBeenCalledWith({ userVisibleOnly: true, applicationServerKey: applicationServerKey(publicKey) }); expect(fixture.binding()).toEqual({ subscriptionId: "71", bindingVersion: "2" });
  });
  it("enables a newly registered device whose server binding version starts at zero", async () => {
    const fixture = browser();
    const initialDevice = { ...device, bindingVersion: "0" };
    vi.stubGlobal("fetch", fakeFetch(initialDevice));
    expect(await enableCurrentDevice(publicKey, "처음 등록한 휴대폰")).toEqual(initialDevice);
    expect(fixture.binding()).toEqual({ subscriptionId: initialDevice.id, bindingVersion: "0" });
    expect(fixture.subscription.unsubscribe).not.toHaveBeenCalled();
  });
  it("never repeats denied requests or contacts the server/worker while offline", async () => {
    const denied = browser({ permission: "denied" }); await expect(enableCurrentDevice(publicKey, "fixture")).rejects.toMatchObject({ name: "NotAllowedError" }); expect(denied.notification.requestPermission).not.toHaveBeenCalled(); expect(denied.worker.register).not.toHaveBeenCalled();
    const offline = browser(); Object.assign(navigator, { onLine: false }); const fetchMock = vi.fn(); vi.stubGlobal("fetch", fetchMock); await expect(enableCurrentDevice(publicKey, "fixture")).rejects.toMatchObject({ code: "OFFLINE" }); expect(offline.notification.requestPermission).not.toHaveBeenCalled(); expect(fetchMock).not.toHaveBeenCalled();
  });
  it("leaves private payloads unbound after a lost registration response and clears only this browser on logout", async () => {
    const fixture = browser({ permission: "granted" }); const fetchMock = vi.fn().mockImplementation(async (path: string) => { if (path.endsWith("/csrf")) return response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }); throw new TypeError("lost response"); }); vi.stubGlobal("fetch", fetchMock);
    await expect(enableCurrentDevice(publicKey, "fixture")).rejects.toThrow("lost response"); expect(fixture.binding()).toBeNull(); expect(fixture.notification.requestPermission).not.toHaveBeenCalled();
    vi.stubGlobal("fetch", fakeFetch(device)); await enableCurrentDevice(publicKey, "fixture"); expect(fixture.binding()).not.toBeNull(); const count = fetchMock.mock.calls.length; await clearCurrentPushBinding(); expect(fixture.binding()).toBeNull(); expect(fetchMock.mock.calls).toHaveLength(count);
  });
  it("rejects invalid VAPID key formats and lengths before any permission prompt", async () => {
    for (const key of ["", "not-a-key", "../invalid", Buffer.from([4, 1]).toString("base64url")]) expect(() => applicationServerKey(key)).toThrow();
    const fixture = browser(); await expect(enableCurrentDevice("invalid", "fixture")).rejects.toThrow(); expect(fixture.notification.requestPermission).not.toHaveBeenCalled();
  });
  it("disables a different device without touching the current browser, and preserves it on rejected deletes", async () => {
    const fixture = browser(); const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : new Response(null, { status: 204 })); vi.stubGlobal("fetch", fetchMock);
    expect(await disablePushDevice({ ...device, currentDevice: false })).toEqual({ browserCleanupComplete: true }); expect(fixture.worker.getRegistration).not.toHaveBeenCalled(); expect(fixture.binding()).not.toBeNull();
    fetchMock.mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : Response.json({ error: { code: "RESOURCE_NOT_FOUND", message: "untrusted" } }, { status: 404 }));
    await expect(disablePushDevice(device)).rejects.toBeInstanceOf(ApiError); expect(fixture.worker.getRegistration).not.toHaveBeenCalled(); expect(fixture.binding()).not.toBeNull();
  });
  it("reports a confirmed server disconnect even if browser unsubscribe fails", async () => {
    const fixture = browser(); fixture.registration.pushManager.getSubscription.mockResolvedValue(fixture.subscription); fixture.subscription.unsubscribe.mockRejectedValue(new Error("browser unavailable"));
    vi.stubGlobal("fetch", vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : new Response(null, { status: 204 })));
    expect(await disablePushDevice(device)).toEqual({ browserCleanupComplete: false }); expect(fixture.binding()).toBeNull();
  });
  it("logs out on the server even if binding storage rejects cleanup, and still attempts browser unsubscribe", async () => {
    const fixture = browser({ permission: "granted" });
    fixture.registration.pushManager.getSubscription.mockResolvedValue(fixture.subscription);
    fixture.registration.active.postMessage.mockImplementation((_message, ports) => { ports[0].postMessage({ ok: false, binding: null }); ports[0].close(); });
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    expect(await logoutWithPushCleanup()).toEqual({ browserCleanupComplete: false });
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/v1/auth/logout")).toHaveLength(1);
    expect(fixture.subscription.unsubscribe).toHaveBeenCalledOnce();
    expect(fetchMock.mock.calls.some(([path]) => path.includes("push-subscriptions"))).toBe(false);
  });
  it("attempts server logout before a nonresponsive worker's timeout without replaying the mutation", async () => {
    vi.useFakeTimers();
    try {
      const fixture = browser({ permission: "granted" });
      fixture.registration.pushManager.getSubscription.mockResolvedValue(fixture.subscription);
      fixture.registration.active.postMessage.mockImplementation((_message, ports) => { ports[0].close(); });
      const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : new Response(null, { status: 204 }));
      vi.stubGlobal("fetch", fetchMock);
      const task = logoutWithPushCleanup();
      await vi.waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path === "/api/v1/auth/logout")).toHaveLength(1));
      expect(fixture.subscription.unsubscribe).not.toHaveBeenCalled();
      await vi.advanceTimersByTimeAsync(5000);
      expect(await task).toEqual({ browserCleanupComplete: false });
      expect(fixture.subscription.unsubscribe).toHaveBeenCalledOnce();
      expect(fetchMock.mock.calls.filter(([path]) => path === "/api/v1/auth/logout")).toHaveLength(1);
    } finally { vi.useRealTimers(); }
  });
  it("propagates an unsuccessful server logout instead of reporting local cleanup as logout success", async () => {
    const fixture = browser({ permission: "granted" });
    fixture.registration.pushManager.getSubscription.mockResolvedValue(fixture.subscription);
    const fetchMock = vi.fn().mockImplementation(async (path: string) => path.endsWith("/csrf") ? response({ headerName: "X-CSRF-TOKEN", token: "fixture-csrf" }) : Response.json({ error: { code: "REQUEST_FAILED", message: "private endpoint" } }, { status: 503 }));
    vi.stubGlobal("fetch", fetchMock);
    await expect(logoutWithPushCleanup()).rejects.toMatchObject({ status: 503, code: "REQUEST_FAILED" });
    await vi.waitFor(() => expect(fixture.subscription.unsubscribe).toHaveBeenCalledOnce());
    expect(fixture.binding()).toBeNull();
    expect(fetchMock.mock.calls.filter(([path]) => path === "/api/v1/auth/logout")).toHaveLength(1);
  });
});
describe("Push settings diary UI and account privacy", () => {
  function render(current: Member | null, enabled = true, failure = false) {
    const client = new QueryClient(); const scope = directoryScope(member); client.setQueryData(memberQueryKey, current); client.setQueryData(pushPublicKeyQuery(scope).queryKey, { enabled, publicKey: enabled ? publicKey : null }); client.setQueryData(pushDevicesQuery(scope).queryKey, { subscriptions: [device] });
    if (failure) client.getQueryCache().find({ queryKey: pushDevicesQuery(scope).queryKey })!.setState({ status: "error", error: new ApiError(403, "FORBIDDEN", "untrusted") });
    const html = renderToStaticMarkup(createElement(QueryClientProvider, { client }, createElement(SessionProvider, null, createElement(PushSettingsPage)))); client.clear(); return html;
  }
  it("renders own devices, gesture-only action, device logout policy and honest installation/delivery guidance", () => {
    const html = render(member); expect(html).toContain(device.deviceName); expect(html).toContain("현재 기기"); expect(html).toContain("이 기기 알림 끄기"); expect(html).toContain("다른 기기는 그대로 유지"); expect(html).toContain("HTTPS"); expect(html).toContain("홈 화면에 추가"); expect(html).toContain("실제 알림이 늦게 도착"); expect(html).toContain("지난 알림을 다시 보내지"); expect(html).toContain("JEONGEUM · SENTIMENTAL MUSIC"); expect(html).not.toContain("fixture-auth"); expect(html).not.toContain("bindingVersion");
  });
  it("shows disabled server and HTTP LAN guidance without claiming Push ready", () => {
    expect(render(member, false)).toContain("서버의 Push 발송은 꺼져"); const html = renderToStaticMarkup(createElement(CapabilityNotice, { capability: "INSECURE" })); expect(html).toContain("HTTP"); expect(html).toContain("예약과 내 알림함은 지금도 이용");
  });
  it("hides previous device data from anonymous, inactive, different account and failed access", () => {
    for (const current of [null, { ...member, status: "INACTIVE" } as unknown as Member, { ...member, id: "another-member" }]) expect(render(current)).not.toContain(device.deviceName);
    expect(render(member, true, true)).not.toContain(device.deviceName);
  });
  it("escapes device names and allows removal of the specified device only", () => {
    const html = renderToStaticMarkup(createElement(PushDeviceCard, { device: { ...device, deviceName: "<script>x</script>", currentDevice: false }, pending: false, onRemove: vi.fn() })); expect(html).toContain("&lt;script&gt;"); expect(html).not.toContain("<script>"); expect(html).not.toContain("이 기기 알림 끄기");
  });
  it("prunes devices/key queries on account/membership transitions and logout", async () => {
    const client = new QueryClient(); const key = pushDevicesQuery(directoryScope(member)).queryKey; client.setQueryData(key, { subscriptions: [device] }); await pruneDirectoryCache(client, { ...member, id: "next-account" }); expect(client.getQueryData(key)).toBeUndefined(); client.setQueryData(key, { subscriptions: [device] }); await replaceSessionCache(client, null); expect(client.getQueryData(key)).toBeUndefined(); client.clear();
  });
  it("refreshes server-confirmed settings and maps errors without disclosing endpoint/server messages", async () => {
    const client = new QueryClient(); const key = pushDevicesQuery("scope").queryKey; client.setQueryData(key, { subscriptions: [] }); await refreshPush(client); expect(client.getQueryState(key)?.isInvalidated).toBe(true); client.clear();
    for (const error of [new ApiError(0, "OFFLINE", "private secret endpoint"), new ApiError(403, "FORBIDDEN", "private secret endpoint"), new ApiError(503, "REQUEST_FAILED", "private secret endpoint")]) expect(pushErrorMessage(error)).not.toContain("secret");
    expect(pushErrorMessage(new ApiError(503, "PUSH_DISABLED", ""))).toContain("발송이 꺼져"); expect(pushErrorMessage(new DOMException("untrusted", "NotAllowedError"))).toContain("허용되지");
  });
  it("provides a standalone manifest with actual brand icons and a same-origin start URL", () => { const result = manifest(); expect(result.name).toBe("정음"); expect(result.short_name).toBe("정음"); expect(result.display).toBe("standalone"); expect(result.start_url).toBe("/"); expect(result.icons?.map((icon) => icon.sizes)).toEqual(["192x192", "512x512", "512x512"]); expect(result.icons?.map((icon) => icon.purpose)).toEqual(["any", "any", "maskable"]); expect(result.description).toContain("JEONGEUM · SENTIMENTAL MUSIC"); });
});
