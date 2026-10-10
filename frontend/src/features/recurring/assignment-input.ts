import type { Team } from "@/features/directory/types";
import type { Assignment, AssignmentPreviewInput, AssignmentRevision } from "./types";

export function revisionAt(assignment: Assignment, date: string): AssignmentRevision | undefined {
  return [...assignment.revisions].sort((a, b) => b.effectiveDate.localeCompare(a.effectiveDate) || b.revisionNo - a.revisionNo).find((revision) => revision.effectiveDate <= date) ?? nextRevisionAfter(assignment, date);
}
export function nextRevisionAfter(assignment: Assignment, date: string): AssignmentRevision | undefined {
  return [...assignment.revisions].sort((a, b) => a.effectiveDate.localeCompare(b.effectiveDate) || b.revisionNo - a.revisionNo).find((revision) => revision.effectiveDate > date && (!assignment.endDate || revision.effectiveDate <= assignment.endDate));
}
export function assignmentInputError(input: AssignmentPreviewInput, today: string, teams: Team[]): string | null {
  if (!teams.some((team) => team.id === input.teamId && team.status === "ACTIVE")) return "활성 팀을 선택해 주세요.";
  if (!Number.isInteger(input.weekday) || input.weekday < 1 || input.weekday > 7) return "요일을 선택해 주세요.";
  if (!/^B(0[1-9]|10)$/.test(input.blockCode)) return "정해진 시간 블록을 선택해 주세요.";
  if (!/^\d{4}-\d{2}-\d{2}$/.test(input.effectiveDate) || Number.isNaN(Date.parse(`${input.effectiveDate}T00:00:00Z`)) || new Date(`${input.effectiveDate}T00:00:00Z`).toISOString().slice(0, 10) !== input.effectiveDate || input.effectiveDate < today) return "적용 시작일은 한국 날짜 기준 오늘 이후로 선택해 주세요.";
  return null;
}
export const previewInputKey = (input: AssignmentPreviewInput) => JSON.stringify([input.assignmentId ?? null, input.expectedVersion ?? null, input.teamId, input.weekday, input.blockCode, input.effectiveDate]);
