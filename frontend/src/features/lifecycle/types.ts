export type LifecycleKind = "WITHDRAW" | "DISBAND";
export interface LifecycleTarget { kind: LifecycleKind; id: string; name: string; version: number }
export interface LifecycleIntent { kind: LifecycleKind; id: string; input: { expectedVersion: number; reason?: string } }
export interface LifecycleResult {
  operationId: string;
  targetId: string;
  status: "WITHDRAWN" | "DISBANDED";
  version: number;
  canceledReservations: number;
  invalidatedExchanges: number;
  endedAssignments: number;
  deactivatedSubscriptions: number;
  revokedSessions: number;
}
