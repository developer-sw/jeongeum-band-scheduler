import { ApiError } from "@/lib/api/client";
import { authErrorMessage } from "@/features/auth/errors";
import type { AssignmentConflict, Weekday } from "./types";
import type { BlockCode } from "@/features/reservations/types";

const messages: Record<string, string> = {
  ASSIGNMENT_CONFLICT: "다른 고정 배정이나 기존 예약과 겹쳐 적용하지 않았어요. 기존 예약·배정·개별 예외는 유지돼요. 충돌 목록을 확인해 주세요.",
  ASSIGNMENT_ENDED: "이미 종료가 지정된 고정 배정이에요. 목록을 새로 확인해 주세요.",
  INVALID_BLOCK: "정해진 시간 블록을 선택해 주세요.",
  TEAM_INACTIVE: "현재 배정할 수 없는 팀이에요. 활성 팀을 선택해 주세요.",
  RESERVATION_TEMPORARILY_UNAVAILABLE: "배정 확인이 지연되고 있어요. 같은 요청 결과 다시 확인으로 처리 여부를 확인해 주세요.",
};
export function assignmentErrorMessage(error: unknown): string { return error instanceof ApiError && messages[error.code] ? messages[error.code] : authErrorMessage(error); }
export function staleAssignmentReloadNotice(error: unknown, uncertain: boolean): string | null {
  return !uncertain && error instanceof ApiError && error.code === "VERSION_CONFLICT"
    ? "이전 편집안을 닫았어요. 최신 배정 목록의 변경 내용을 확인한 뒤 패턴 수정 또는 종료 지정을 다시 열어 주세요."
    : null;
}
export function assignmentConflicts(error: unknown): AssignmentConflict[] {
  if (!(error instanceof ApiError) || error.code !== "ASSIGNMENT_CONFLICT") return [];
  return (error.details.conflicts ?? []).flatMap((value) => {
    if (!value || typeof value !== "object") return [];
    const item = value as Record<string, unknown>;
    if (!["PATTERN", "RESERVATION"].includes(String(item.type)) || !Number.isInteger(item.weekday) || Number(item.weekday) < 1 || Number(item.weekday) > 7 || typeof item.blockCode !== "string" || !/^B(0[1-9]|10)$/.test(item.blockCode)) return [];
    const safeString = (field: string) => typeof item[field] === "string" ? item[field] as string : null;
    const date = safeString("date");
    return [{ type: item.type as AssignmentConflict["type"], weekday: item.weekday as Weekday, blockCode: item.blockCode as BlockCode, assignmentId: safeString("assignmentId"), reservationId: safeString("reservationId"), teamId: safeString("teamId"), teamName: safeString("teamName"), date: date && /^\d{4}-\d{2}-\d{2}$/.test(date) && !Number.isNaN(Date.parse(date)) ? date : null }];
  });
}
