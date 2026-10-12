"use client";

import { useEffect, useRef, useState } from "react";
import { IdempotentRequestState, type PendingIdempotentRequest } from "@/lib/api/idempotent-request-state";
import { submitExchangeIntent } from "./api";
import { exchangeErrorMessage } from "./errors";
import type { ExchangeIntent, ExchangeResponse } from "./types";

export function useExchangeAction() {
  const controller = useRef<AbortController | null>(null);
  const [requests] = useState(() => new IdempotentRequestState<ExchangeIntent>());
  const [pending, setPending] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const [error, setError] = useState<string | null>(null);
  useEffect(() => () => { controller.current?.abort(); requests.clear(); }, [requests]);
  async function send(request: PendingIdempotentRequest<ExchangeIntent>): Promise<ExchangeResponse | undefined> {
    if (controller.current) return;
    const active = new AbortController(); controller.current = active;
    setPending(true); setError(null);
    try { const result = await submitExchangeIntent(request.intent, request.key, active.signal); requests.clear(); setUncertain(false); return result; }
    catch (failure) { if (!active.signal.aborted) { setUncertain(requests.failed(failure)); setError(exchangeErrorMessage(failure)); } }
    finally { controller.current = null; if (!active.signal.aborted) setPending(false); }
  }
  async function run(intent: ExchangeIntent) { if (controller.current) return; const request = requests.prepare(intent); return request ? send(request) : undefined; }
  return { run, retry: () => requests.request ? send(requests.request) : Promise.resolve(undefined), pending, uncertain, error };
}
