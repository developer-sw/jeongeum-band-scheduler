"use client";
import { useEffect, useRef, useState } from "react";
import { IdempotentRequestState } from "@/lib/api/idempotent-request-state";
import { submitReadIntent } from "./api";
import type { Notification, ReadAllResult, ReadIntent } from "./types";
/** Reading is idempotent; keep the original cutoff if its response is lost. */
export function useReadAction() {
  const controller = useRef<AbortController | null>(null);
  // Read endpoints are naturally idempotent; this internal key is never sent.
  const [requests] = useState(() => new IdempotentRequestState<ReadIntent>(() => "read"));
  const [pending, setPending] = useState(false); const [uncertain, setUncertain] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  useEffect(() => () => { controller.current?.abort(); requests.clear(); }, [requests]);
  async function send(): Promise<Notification | ReadAllResult | undefined> {
    if (controller.current || !requests.request) return undefined;
    const active = new AbortController(); controller.current = active; setPending(true); setFailure(null);
    try { const result = await submitReadIntent(requests.request.intent, active.signal); if (active.signal.aborted) return undefined; requests.clear(); setUncertain(false); return result; }
    catch (error) { if (!active.signal.aborted) { setUncertain(requests.failed(error)); setFailure(error); } return undefined; }
    finally { controller.current = null; if (!active.signal.aborted) setPending(false); }
  }
  const run = (next: ReadIntent) => { if (controller.current || !requests.prepare(next)) return Promise.resolve(undefined); return send(); };
  return { run, retry: send, pending, uncertain, failure };
}
