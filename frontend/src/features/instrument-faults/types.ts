import type { Instrument, InstrumentCode } from "@/features/reservations/types";

export const instrumentCodes: InstrumentCode[] = ["VOCAL", "KEYBOARD", "ELECTRIC_GUITAR", "BASS", "DRUMS", "ACOUSTIC_GUITAR"];
export interface InstrumentFault {
  id: string;
  instrumentCode: InstrumentCode;
  instrumentName: string;
  status: "OPEN" | "RESOLVED";
  description: string;
  origin: "ADMIN" | "LEGACY";
  reportedByUserId: string | null;
  reportedAt: string;
  resolvedByUserId: string | null;
  resolvedAt: string | null;
  resolutionNote: string | null;
  version: number;
  assetVersion: number;
}
export interface InstrumentFaultList { serverTime: string; incidents: InstrumentFault[]; nextBeforeId: string | null }
export interface ReportFaultInput { instrumentCode: InstrumentCode; expectedAssetVersion: number; description: string }
export interface ResolveFaultInput { expectedVersion: number; expectedAssetVersion: number; resolutionNote?: string }
export type FaultIntent = { kind: "REPORT"; input: ReportFaultInput } | { kind: "RESOLVE"; incidentId: string; input: ResolveFaultInput };
export type FaultEditorSelection = { kind: "REPORT"; instrument: Instrument } | { kind: "RESOLVE"; instrument: Instrument; incident: InstrumentFault };
