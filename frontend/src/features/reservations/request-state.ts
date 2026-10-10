import { ApiError } from "@/lib/api/client";
import { isUncertainReservationFailure } from "./errors";
import type { ReservationIntent } from "./types";

export interface PendingReservationRequest { key: string; intent: ReservationIntent }

export class ReservationRequestState {
  request: PendingReservationRequest | null = null;
  uncertain = false;
  constructor(private readonly createKey: () => string = () => crypto.randomUUID()) {}

  prepare(intent: ReservationIntent): PendingReservationRequest | undefined {
    if (this.request) return undefined;
    this.request = { key: this.createKey(), intent: structuredClone(intent) };
    return this.request;
  }
  failed(error: unknown): boolean {
    // A rejected retry's offline/CSRF/rate-limit preparation cannot settle an
    // earlier transport failure whose transaction may already have committed.
    const preparationFailed = error instanceof ApiError && ["OFFLINE", "CSRF_INVALID", "INVALID_CSRF_RESPONSE", "TOO_MANY_REQUESTS"].includes(error.code);
    this.uncertain = isUncertainReservationFailure(error) || (this.uncertain && preparationFailed);
    if (!this.uncertain) this.request = null;
    return this.uncertain;
  }
  clear() { this.request = null; this.uncertain = false; }
}
