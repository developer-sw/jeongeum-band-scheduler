import type { FaultEditorSelection, FaultIntent } from "./types";

export function faultInputError(selection: FaultEditorSelection, text: string): string | null {
  const value = text.trim();
  if (selection.kind === "REPORT" && !value) return "고장 사유를 입력해 주세요.";
  if (value.length > 1000) return "고장 사유와 복구 메모는 각각 1000자 이내로 입력해 주세요.";
  return null;
}
export function faultIntent(selection: FaultEditorSelection, text: string): FaultIntent {
  const value = text.trim();
  return selection.kind === "REPORT"
    ? { kind: "REPORT", input: { instrumentCode: selection.instrument.code, expectedAssetVersion: selection.instrument.version, description: value } }
    : { kind: "RESOLVE", incidentId: selection.incident.id, input: { expectedVersion: selection.incident.version, expectedAssetVersion: selection.instrument.version, ...(value ? { resolutionNote: value } : {}) } };
}
export function faultSelectionChanged(selection: FaultEditorSelection, assets: { code: string; version: number }[], open: { id: string; version: number }[]) {
  return assets.find((asset) => asset.code === selection.instrument.code)?.version !== selection.instrument.version
    || selection.kind === "RESOLVE" && open.find((incident) => incident.id === selection.incident.id)?.version !== selection.incident.version;
}
