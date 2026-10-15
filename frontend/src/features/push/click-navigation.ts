import { getPushDevices } from "./api";
import { pushWorker, takePendingPushClick, workerBinding } from "./browser";

/** Only an actual notification click can leave an intent in the verified worker. */
export async function restorePendingPushClick(signal?: AbortSignal) {
  if (signal?.aborted || document.visibilityState !== "visible") return;
  const registration = await pushWorker();
  if (!registration || signal?.aborted) return;
  const binding = await workerBinding(registration, "READ");
  if (!binding || signal?.aborted) return;
  const devices = await getPushDevices(signal);
  if (signal?.aborted) return;
  if (!devices.subscriptions.some((device) => device.active && device.currentDevice && device.id === binding.subscriptionId && device.bindingVersion === binding.bindingVersion)) {
    await workerBinding(registration, null);
    return;
  }
  const path = await takePendingPushClick(registration);
  if (!path || signal?.aborted) return;
  if (`${window.location.pathname}${window.location.search}` !== path) window.location.assign(path);
}

/** A message is only a wake-up signal; its payload never supplies a URL. */
export function watchPushClicks(recover: () => void) {
  const onMessage = (event: MessageEvent) => { if (event.data?.type === "JEONGEUM_PUSH_CLICK") recover(); };
  window.addEventListener("focus", recover);
  window.addEventListener("pageshow", recover);
  document.addEventListener("visibilitychange", recover);
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.addEventListener("message", onMessage);
    navigator.serviceWorker.addEventListener("controllerchange", recover);
  }
  return () => {
    window.removeEventListener("focus", recover);
    window.removeEventListener("pageshow", recover);
    document.removeEventListener("visibilitychange", recover);
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.removeEventListener("message", onMessage);
      navigator.serviceWorker.removeEventListener("controllerchange", recover);
    }
  };
}
