import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getPushDevices } from "./api";
import { pushWorker, takePendingPushClick, workerBinding } from "./browser";
import { restorePendingPushClick, watchPushClicks } from "./click-navigation";
import type { PushBinding, PushDevice } from "./types";

vi.mock("./api", () => ({ getPushDevices: vi.fn() }));
vi.mock("./browser", () => ({ pushWorker: vi.fn(), takePendingPushClick: vi.fn(), workerBinding: vi.fn() }));

const binding: PushBinding = { subscriptionId: "71", bindingVersion: "2" };
const device: PushDevice = {
  id: "71", deviceName: "합성 클릭 검증 기기", active: true, currentDevice: true, bindingVersion: "2",
  createdAt: "2026-10-15T03:00:00Z", updatedAt: "2026-10-15T03:00:00Z",
};
const target = "/reservations?reservationId=41";
const registration = { scope: "https://jeongeum.example/" } as ServiceWorkerRegistration;

function browser() {
  const assign = vi.fn();
  const location = { pathname: "/notifications/settings", search: "", assign };
  const windowEvents = Object.assign(new EventTarget(), { location });
  const documentEvents = Object.assign(new EventTarget(), { visibilityState: "visible" as DocumentVisibilityState });
  const serviceWorker = new EventTarget();
  vi.stubGlobal("window", windowEvents);
  vi.stubGlobal("document", documentEvents);
  vi.stubGlobal("navigator", { serviceWorker });
  return { assign, location, windowEvents, documentEvents, serviceWorker };
}

function message(target: EventTarget, data: unknown) {
  target.dispatchEvent(Object.assign(new Event("message"), { data }));
}

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(pushWorker).mockResolvedValue(registration);
  vi.mocked(workerBinding).mockResolvedValue(binding);
  vi.mocked(getPushDevices).mockResolvedValue({ subscriptions: [device] });
  vi.mocked(takePendingPushClick).mockResolvedValue(target);
});
afterEach(() => vi.unstubAllGlobals());

describe("Verified pending Push click navigation", () => {
  it("consumes one pending destination only after confirming the current active device with the server", async () => {
    const fixture = browser(); const controller = new AbortController();
    await restorePendingPushClick(controller.signal);
    expect(pushWorker).toHaveBeenCalledOnce();
    expect(workerBinding).toHaveBeenCalledExactlyOnceWith(registration, "READ");
    expect(getPushDevices).toHaveBeenCalledExactlyOnceWith(controller.signal);
    expect(takePendingPushClick).toHaveBeenCalledExactlyOnceWith(registration);
    expect(fixture.assign).toHaveBeenCalledExactlyOnceWith(target);
    expect(vi.mocked(getPushDevices).mock.invocationCallOrder[0]).toBeLessThan(vi.mocked(takePendingPushClick).mock.invocationCallOrder[0]);
  });

  it("consumes a pending click without reloading the page that is already its exact destination", async () => {
    const fixture = browser(); fixture.location.pathname = "/reservations"; fixture.location.search = "?reservationId=41";
    await restorePendingPushClick();
    expect(takePendingPushClick).toHaveBeenCalledOnce();
    expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("compares the search string so another reservation on the same page still opens", async () => {
    const fixture = browser(); fixture.location.pathname = "/reservations"; fixture.location.search = "?reservationId=42";
    await restorePendingPushClick();
    expect(fixture.assign).toHaveBeenCalledExactlyOnceWith(target);
  });

  it("leaves the pending click untouched while the document is hidden", async () => {
    const fixture = browser(); fixture.documentEvents.visibilityState = "hidden";
    await restorePendingPushClick();
    expect(pushWorker).not.toHaveBeenCalled(); expect(getPushDevices).not.toHaveBeenCalled();
    expect(takePendingPushClick).not.toHaveBeenCalled(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("does no work for an already aborted recovery", async () => {
    const fixture = browser(); const controller = new AbortController(); controller.abort();
    await restorePendingPushClick(controller.signal);
    expect(pushWorker).not.toHaveBeenCalled(); expect(getPushDevices).not.toHaveBeenCalled();
    expect(takePendingPushClick).not.toHaveBeenCalled(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("does not consult the server or consume an intent without the verified worker registration", async () => {
    const fixture = browser(); vi.mocked(pushWorker).mockResolvedValue(undefined);
    await restorePendingPushClick();
    expect(workerBinding).not.toHaveBeenCalled(); expect(getPushDevices).not.toHaveBeenCalled();
    expect(takePendingPushClick).not.toHaveBeenCalled(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("leaves the intent unconsumed when there is no current worker account binding", async () => {
    const fixture = browser(); vi.mocked(workerBinding).mockResolvedValue(null);
    await restorePendingPushClick();
    expect(getPushDevices).not.toHaveBeenCalled(); expect(takePendingPushClick).not.toHaveBeenCalled();
    expect(fixture.assign).not.toHaveBeenCalled();
  });

  it.each([
    ["another account's device", { ...device, id: "72" }],
    ["a different login session", { ...device, currentDevice: false }],
    ["a revoked subscription", { ...device, active: false }],
    ["a replaced binding version", { ...device, bindingVersion: "3" }],
  ] as const)("clears a stale worker binding without taking its intent for %s", async (_scenario, current) => {
    const fixture = browser(); vi.mocked(getPushDevices).mockResolvedValue({ subscriptions: [current] });
    await restorePendingPushClick();
    expect(workerBinding).toHaveBeenNthCalledWith(1, registration, "READ");
    expect(workerBinding).toHaveBeenNthCalledWith(2, registration, null);
    expect(takePendingPushClick).not.toHaveBeenCalled(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("clears the worker binding when the signed-in account has no registered devices", async () => {
    const fixture = browser(); vi.mocked(getPushDevices).mockResolvedValue({ subscriptions: [] });
    await restorePendingPushClick();
    expect(workerBinding).toHaveBeenLastCalledWith(registration, null);
    expect(takePendingPushClick).not.toHaveBeenCalled(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("does not consume the destination when current server authentication cannot be confirmed", async () => {
    const fixture = browser(); vi.mocked(getPushDevices).mockRejectedValue(new Error("Synthetic authentication unavailable"));
    await expect(restorePendingPushClick()).rejects.toThrow("Synthetic authentication unavailable");
    expect(takePendingPushClick).not.toHaveBeenCalled(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it("does not navigate when the verified worker has no pending click", async () => {
    const fixture = browser(); vi.mocked(takePendingPushClick).mockResolvedValue(null);
    await restorePendingPushClick();
    expect(takePendingPushClick).toHaveBeenCalledOnce(); expect(fixture.assign).not.toHaveBeenCalled();
  });

  it.each(["registration", "binding", "devices", "intent"] as const)("stops navigation when canceled during the awaited %s lookup", async (stage) => {
    const fixture = browser(); const controller = new AbortController();
    if (stage === "registration") vi.mocked(pushWorker).mockImplementation(async () => { controller.abort(); return registration; });
    if (stage === "binding") vi.mocked(workerBinding).mockImplementation(async () => { controller.abort(); return binding; });
    if (stage === "devices") vi.mocked(getPushDevices).mockImplementation(async () => { controller.abort(); return { subscriptions: [device] }; });
    if (stage === "intent") vi.mocked(takePendingPushClick).mockImplementation(async () => { controller.abort(); return target; });
    await restorePendingPushClick(controller.signal);
    expect(fixture.assign).not.toHaveBeenCalled();
    if (stage !== "intent") expect(takePendingPushClick).not.toHaveBeenCalled();
    if (stage === "registration" || stage === "binding") expect(getPushDevices).not.toHaveBeenCalled();
  });
});

describe("Push click recovery wake-up listeners", () => {
  it("wakes recovery on window, visibility and worker lifecycle signals and removes every listener", () => {
    const fixture = browser(); const recover = vi.fn(); const stop = watchPushClicks(recover);
    fixture.windowEvents.dispatchEvent(new Event("focus"));
    fixture.windowEvents.dispatchEvent(new Event("pageshow"));
    fixture.documentEvents.dispatchEvent(new Event("visibilitychange"));
    fixture.serviceWorker.dispatchEvent(new Event("controllerchange"));
    message(fixture.serviceWorker, { type: "JEONGEUM_PUSH_CLICK" });
    expect(recover).toHaveBeenCalledTimes(5);
    stop();
    fixture.windowEvents.dispatchEvent(new Event("focus"));
    fixture.windowEvents.dispatchEvent(new Event("pageshow"));
    fixture.documentEvents.dispatchEvent(new Event("visibilitychange"));
    fixture.serviceWorker.dispatchEvent(new Event("controllerchange"));
    message(fixture.serviceWorker, { type: "JEONGEUM_PUSH_CLICK" });
    expect(recover).toHaveBeenCalledTimes(5);
  });

  it("ignores unrelated worker messages and never uses a message-supplied URL as a destination", async () => {
    const fixture = browser(); const tasks: Promise<void>[] = [];
    const recover = vi.fn(() => { tasks.push(restorePendingPushClick()); });
    const stop = watchPushClicks(recover);
    message(fixture.serviceWorker, null);
    message(fixture.serviceWorker, { type: "OTHER_MESSAGE", targetPath: "https://outside.example/" });
    expect(recover).not.toHaveBeenCalled();
    message(fixture.serviceWorker, { type: "JEONGEUM_PUSH_CLICK", targetPath: "https://outside.example/", url: "//outside.example/" });
    await Promise.all(tasks);
    expect(recover).toHaveBeenCalledOnce();
    expect(takePendingPushClick).toHaveBeenCalledExactlyOnceWith(registration);
    expect(fixture.assign).toHaveBeenCalledExactlyOnceWith(target);
    stop();
  });

  it("still observes window wake-ups when service workers are unavailable", () => {
    const fixture = browser(); vi.stubGlobal("navigator", {});
    const recover = vi.fn(); const stop = watchPushClicks(recover);
    fixture.windowEvents.dispatchEvent(new Event("pageshow"));
    expect(recover).toHaveBeenCalledOnce();
    stop(); fixture.windowEvents.dispatchEvent(new Event("pageshow"));
    expect(recover).toHaveBeenCalledOnce();
  });
});
