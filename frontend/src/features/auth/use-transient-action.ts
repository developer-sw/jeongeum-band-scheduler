"use client";

import { useEffect, useRef, useState } from "react";
import { authErrorMessage } from "./errors";

export function useTransientAction() {
  const controller = useRef<AbortController | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => () => { controller.current?.abort(); }, []);

  async function run<T>(task: (signal: AbortSignal) => Promise<T>): Promise<T | undefined> {
    if (controller.current) return undefined;
    const active = new AbortController();
    controller.current = active;
    setPending(true);
    setError(null);
    try { return await task(active.signal); }
    catch (failure) {
      if (!active.signal.aborted) setError(authErrorMessage(failure));
      return undefined;
    } finally {
      controller.current = null;
      if (!active.signal.aborted) setPending(false);
    }
  }

  return { run, pending, error, clearError: () => setError(null) };
}
