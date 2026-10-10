import type { BlockCode } from "@/features/reservations/types";
import type { TeamKind } from "@/features/directory/types";

export type Weekday = 1 | 2 | 3 | 4 | 5 | 6 | 7;
export type OccurrenceState = "PLANNED" | "MATERIALIZED" | "CANCELED_EXCEPTION" | "CHANGED_EXCEPTION" | "EXCHANGED_EXCEPTION" | "ENDED";
export interface AssignmentRevision { id: string; revisionNo: number; effectiveDate: string; weekday: Weekday; blockCode: BlockCode }
export interface AssignmentOccurrence { id: string; weekStartDate: string; revisionId: string; originalScheduledDate: string; originalBlockCode: BlockCode; state: OccurrenceState; reservationIds: string[] }
export interface Assignment {
  id: string; teamId: string; teamName: string; teamKind: TeamKind; status: "ACTIVE" | "ENDED"; version: number;
  createdByUserId: string; endDate: string | null; revisions: AssignmentRevision[]; occurrences: AssignmentOccurrence[];
}
export interface AssignmentList { assignments: Assignment[]; serverTime: string; receivedAtMillis: number }
export interface AssignmentInput { teamId: string; weekday: Weekday; blockCode: BlockCode; effectiveDate: string }
export interface AssignmentPreviewInput extends AssignmentInput { assignmentId?: string; expectedVersion?: number }
export interface AssignmentUpdateInput { expectedVersion: number; weekday: Weekday; blockCode: BlockCode; effectiveDate: string }
export interface AssignmentEndInput { expectedVersion: number; endDate: string }
export interface AssignmentConflict { type: "PATTERN" | "RESERVATION"; assignmentId?: string | null; reservationId?: string | null; date?: string | null; weekday: Weekday; blockCode: BlockCode; teamId?: string | null; teamName?: string | null }
export interface PlannedOccurrence { weekStartDate: string; date: string; blockCode: BlockCode; state: OccurrenceState; preserved: boolean }
export interface AssignmentPreview { canApply: boolean; conflicts: AssignmentConflict[]; plannedOccurrences: PlannedOccurrence[] }
export type AssignmentIntent = { kind: "CREATE"; input: AssignmentInput } | { kind: "UPDATE"; assignmentId: string; input: AssignmentUpdateInput } | { kind: "END"; assignmentId: string; input: AssignmentEndInput };
