import type { BlockCode, InstrumentCode, ReservationDetail, ReservationSummary } from "@/features/reservations/types";

export type ExchangeStatus = "PENDING" | "COMPLETED" | "REJECTED" | "CANCELED" | "EXPIRED" | "INVALIDATED";
export interface ExchangeCandidate extends ReservationSummary {
  bundleKey: string; date: string; blockCode: BlockCode;
  startsAt: string; endsAt: string; quotaMinutes: number; dualAssetException: boolean;
}
export interface ExchangeItemInput { reservationId: string; expectedVersion: number }
export interface CreateExchangeInput { offered: ExchangeItemInput[]; requested: ExchangeItemInput[] }
export interface ExchangePlacement {
  side: "REQUESTER" | "COUNTERPART"; sourceReservationIds: string[];
  subjectType: "PERSONAL" | "TEAM"; purpose: "PERSONAL" | "REGULAR" | "ADDITIONAL";
  teamId: string | null; teamName: string | null; ownerUserId: string | null; creatorUserId: string;
  date: string; blockCode: BlockCode; instrumentCodes: InstrumentCode[];
  allocationRequired: boolean; quotaMinutes: number; teamFaultWarnings: InstrumentCode[];
}
export interface ExchangeResponse {
  id: string; version: number; status: ExchangeStatus; requesterUserId: string;
  expiresAt: string; createdAt: string; processedAt: string | null; processedByUserId: string | null;
  stateReason: string | null; serverTime: string;
  offered: ReservationDetail[]; requested: ReservationDetail[]; preview: ExchangePlacement[];
  allocationReservationIds: string[]; canAllocateReservationIds: string[];
  canApprove: boolean; canReject: boolean; canCancel: boolean;
  replacedReservationIds: string[]; reservations: ReservationDetail[];
}
export interface ExchangeList { serverTime: string; requests: ExchangeResponse[] }
export interface ExchangeCandidates { serverTime: string; reservations: ExchangeCandidate[] }
export interface AllocationInput { expectedVersion: number; reservationId: string; instrumentCodesOrder: InstrumentCode[] }
export type ExchangeIntent = { kind: "CREATE"; input: CreateExchangeInput }
  | { kind: "ALLOCATE"; id: string; input: AllocationInput }
  | { kind: "APPROVE" | "REJECT" | "CANCEL"; id: string; input: { expectedVersion: number } };
