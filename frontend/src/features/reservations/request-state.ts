import { IdempotentRequestState, type PendingIdempotentRequest } from "@/lib/api/idempotent-request-state";
import type { ReservationIntent } from "./types";

export type PendingReservationRequest = PendingIdempotentRequest<ReservationIntent>;
export class ReservationRequestState extends IdempotentRequestState<ReservationIntent> {}
