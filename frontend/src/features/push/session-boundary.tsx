"use client";

import { useEffect, useRef } from "react";
import { useSession } from "@/features/auth/session-provider";
import { getPushDevices } from "./api";
import { pushWorker, workerBinding } from "./browser";
import { restorePendingPushClick, watchPushClicks } from "./click-navigation";

/** Existing browser binding is never silently assigned to a new account. */
export function PushSessionBoundary() {
  const { member, loading } = useSession();
  const previous = useRef<string | null | undefined>(undefined);
  const memberId = member?.id ?? null;
  useEffect(() => {
    if (loading) return;
    const changed = previous.current !== undefined && previous.current !== memberId;
    previous.current = memberId;
    const controller = new AbortController();
    void (async () => {
      const registration = await pushWorker();
      if (!registration || controller.signal.aborted) return;
      if (!memberId || changed) { await workerBinding(registration, null); return; }
      const binding = await workerBinding(registration, "READ");
      if (!binding || controller.signal.aborted) return;
      await workerBinding(registration, null, true);
      if (controller.signal.aborted) return;
      const list = await getPushDevices(controller.signal);
      if (controller.signal.aborted) return;
      if (list.subscriptions.some((device) => device.active && device.currentDevice && device.id === binding.subscriptionId && device.bindingVersion === binding.bindingVersion)) {
        await workerBinding(registration, binding);
        if (controller.signal.aborted) return;
        await restorePendingPushClick(controller.signal);
      }
    })().catch(() => { /* Failed reconciliation never enables a new binding. */ });
    return () => controller.abort();
  }, [memberId, loading]);
  useEffect(() => {
    if (loading || !memberId) return;
    const controller = new AbortController();
    let running = false;
    let again = false;
    const recover = async () => {
      if (controller.signal.aborted) return;
      if (running) { again = true; return; }
      running = true;
      try {
        do {
          again = false;
          await restorePendingPushClick(controller.signal);
        } while (again && !controller.signal.aborted);
      } catch { /* Native click navigation remains available when recovery cannot read storage. */ }
      finally { running = false; }
    };
    const unsubscribe = watchPushClicks(() => { void recover(); });
    return () => { controller.abort(); unsubscribe(); };
  }, [memberId, loading]);
  return null;
}
