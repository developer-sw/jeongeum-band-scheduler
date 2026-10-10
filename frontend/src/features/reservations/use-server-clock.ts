"use client";

import { useSyncExternalStore } from "react";
import { serverNow } from "./time";
import type { ScheduleSnapshot } from "./types";

let tick = 0;
let timer: ReturnType<typeof setInterval> | undefined;
const listeners = new Set<() => void>();
function subscribe(listener: () => void) {
  listeners.add(listener);
  if (!timer) {
    tick = Date.now();
    timer = setInterval(() => { tick = Date.now(); listeners.forEach((notify) => notify()); }, 15_000);
  }
  return () => { listeners.delete(listener); if (!listeners.size && timer) { clearInterval(timer); timer = undefined; } };
}
export function useServerClock(snapshot?: ScheduleSnapshot): number {
  const clientNow = useSyncExternalStore(subscribe, () => tick, () => 0);
  return snapshot ? serverNow(snapshot, clientNow || snapshot.receivedAtMillis) : 0;
}
