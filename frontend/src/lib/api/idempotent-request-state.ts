import { ApiError } from "./client";

export interface PendingIdempotentRequest<T> { key: string; intent: T }

/** Keep a write's exact body/key until an uncertain outcome is explicitly settled. */
export class IdempotentRequestState<T> {
  request: PendingIdempotentRequest<T> | null = null;
  uncertain = false;
  constructor(private readonly createKey: () => string = () => crypto.randomUUID()) {}

  prepare(intent: T): PendingIdempotentRequest<T> | undefined {
    if (this.request) return undefined;
    this.request = { key: this.createKey(), intent: structuredClone(intent) };
    return this.request;
  }
  failed(error: unknown): boolean {
    const preparationFailed = error instanceof ApiError && ["OFFLINE", "CSRF_INVALID", "INVALID_CSRF_RESPONSE", "TOO_MANY_REQUESTS"].includes(error.code);
    const unknownOutcome = !(error instanceof ApiError) || (error.code !== "OFFLINE" && (error.status === 0 || error.status >= 500 || error.status === 408 || error.code === "INVALID_RESPONSE"));
    this.uncertain = unknownOutcome || (this.uncertain && preparationFailed);
    if (!this.uncertain) this.request = null;
    return this.uncertain;
  }
  clear() { this.request = null; this.uncertain = false; }
}
