"use client";
import { useEffect, useRef, useState } from "react";
import { IdempotentRequestState, type PendingIdempotentRequest } from "@/lib/api/idempotent-request-state";
import { submitNoticeIntent } from "./api";
import type { Notice, NoticeIntent } from "./types";
export function useNoticeAction() {
  const controller = useRef<AbortController | null>(null);
  const [requests] = useState(() => new IdempotentRequestState<NoticeIntent>());
  const [pending, setPending] = useState(false); const [uncertain, setUncertain] = useState(false); const [failure, setFailure] = useState<unknown>(null);
  useEffect(() => () => { controller.current?.abort(); requests.clear(); }, [requests]);
  async function send(request: PendingIdempotentRequest<NoticeIntent>): Promise<Notice | undefined> {
    if (controller.current) return undefined;
    const active = new AbortController(); controller.current = active; setPending(true); setFailure(null);
    try { const result = await submitNoticeIntent(request.intent, request.key, active.signal); if (active.signal.aborted) return undefined; requests.clear(); setUncertain(false); return result; }
    catch (error) { if (!active.signal.aborted) { setUncertain(requests.failed(error)); setFailure(error); } return undefined; }
    finally { controller.current = null; if (!active.signal.aborted) setPending(false); }
  }
  const run = (intent: NoticeIntent) => { if (controller.current) return Promise.resolve(undefined); const request = requests.prepare(intent); return request ? send(request) : Promise.resolve(undefined); };
  const retry = () => requests.request ? send(requests.request) : Promise.resolve(undefined);
  return { run, retry, pending, uncertain, failure, clearFailure: () => setFailure(null) };
}
