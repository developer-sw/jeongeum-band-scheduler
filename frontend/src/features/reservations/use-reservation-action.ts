"use client";

import { useEffect, useRef, useState } from "react";
import { submitReservationIntent } from "./api";
import { reservationActionErrorMessage } from "./errors";
import { ReservationRequestState, type PendingReservationRequest } from "./request-state";
import type { ReservationDetail, ReservationIntent } from "./types";

export function useReservationAction() {
  const controller = useRef<AbortController | null>(null);
  const [requests] = useState(() => new ReservationRequestState());
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => () => { controller.current?.abort(); requests.clear(); }, [requests]);

  async function send(request: PendingReservationRequest): Promise<ReservationDetail | undefined> {
    if (controller.current) return undefined;
    const active = new AbortController(); controller.current = active;
    setPending(true); setError(null);
    try {
      const result = await submitReservationIntent(request.intent, request.key, active.signal);
      requests.clear(); setUncertain(false); return result;
    } catch (failure) {
      if (!active.signal.aborted) {
        setUncertain(requests.failed(failure)); setError(reservationActionErrorMessage(failure, request.intent));
      }
      return undefined;
    } finally {
      controller.current = null; if (!active.signal.aborted) setPending(false);
    }
  }
  async function run(intent: ReservationIntent) {
    if (controller.current) return undefined;
    const request = requests.prepare(intent);
    return request ? send(request) : undefined;
  }
  const retry = () => requests.request ? send(requests.request) : Promise.resolve(undefined);
  return { run, retry, pending, uncertain, error, request: requests.request };
}
