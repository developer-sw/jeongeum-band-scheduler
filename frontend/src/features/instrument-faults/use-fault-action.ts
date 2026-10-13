"use client";

import { useEffect, useRef, useState } from "react";
import { IdempotentRequestState, type PendingIdempotentRequest } from "@/lib/api/idempotent-request-state";
import { submitFaultIntent } from "./api";
import type { FaultIntent, InstrumentFault } from "./types";

export function useFaultAction() {
  const controller = useRef<AbortController | null>(null);
  const [requests] = useState(() => new IdempotentRequestState<FaultIntent>());
  const [pending, setPending] = useState(false); const [uncertain, setUncertain] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  useEffect(() => () => { controller.current?.abort(); requests.clear(); }, [requests]);
  async function send(request: PendingIdempotentRequest<FaultIntent>): Promise<InstrumentFault | undefined> {
    if (controller.current) return undefined;
    const active = new AbortController(); controller.current = active; setPending(true); setFailure(null);
    try { const result = await submitFaultIntent(request.intent, request.key, active.signal); if (active.signal.aborted) return undefined; requests.clear(); setUncertain(false); return result; }
    catch (error) { if (!active.signal.aborted) { setUncertain(requests.failed(error)); setFailure(error); } return undefined; }
    finally { controller.current = null; if (!active.signal.aborted) setPending(false); }
  }
  async function run(intent: FaultIntent) { if (controller.current) return undefined; const request = requests.prepare(intent); return request ? send(request) : undefined; }
  const retry = () => requests.request ? send(requests.request) : Promise.resolve(undefined);
  return { run, retry, pending, uncertain, failure, clearFailure: () => setFailure(null) };
}
