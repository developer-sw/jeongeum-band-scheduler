import { ApiError } from "@/lib/api/client";
import { authErrorMessage } from "@/features/auth/errors";
import type { ReservationIntent } from "./types";

const messages: Record<string, string> = {
  VERSION_CONFLICT: "예약이 먼저 변경됐어요. 최신 상세와 시간표를 불러온 뒤 변경 내용을 다시 확인해 주세요.",
  INVALID_BLOCK: "정해진 시간 블록을 선택해 주세요.",
  RESERVATION_CONFLICT: "다른 예약이 먼저 확정됐어요. 최신 시간표를 확인하고 다시 선택해 주세요.",
  PERSONAL_OVERLAP: "이미 같은 시간에 개인 예약이 있어요. 한 사람이 악기 두 개를 각각 예약할 수 없어요.",
  DAILY_LIMIT_EXCEEDED: "이 개인 또는 팀의 하루 60분 블록 한도를 모두 사용했어요. 90분 블록은 별도로 예약할 수 있어요.",
  ASSET_FAULTY: "선택한 악기가 고장 상태예요. 최신 상태를 확인하고 다른 악기를 선택해 주세요.",
  DATE_NOT_OPEN: "지금 예약할 수 없는 날짜예요. 한국 날짜 기준 오늘부터 14일 뒤까지 선택해 주세요.",
  RESERVATION_STARTED: "이미 시작한 예약이에요. 현재 상태를 다시 확인해 주세요.",
  RESERVATION_FINISHED: "이미 종료된 시간이에요. 새 예약이나 취소를 처리할 수 없으며 이용 이력은 보존돼요.",
  RESERVATION_TEMPORARILY_UNAVAILABLE: "예약 확인이 지연되고 있어요. 같은 요청 결과 다시 확인으로 처리 여부를 확인해 주세요.",
  MEMBERSHIP_REQUIRED: "현재 이 팀에 소속돼 있지 않아요. 소속 정보를 다시 확인해 주세요.",
  IDEMPOTENCY_KEY_REUSED: "이전 요청과 내용이 달라 처리하지 못했어요. 최신 예약을 확인하고 다시 선택해 주세요.",
};
export function reservationErrorMessage(error: unknown) { return error instanceof ApiError && messages[error.code] ? messages[error.code] : authErrorMessage(error); }

export function reservationActionErrorMessage(error: unknown, intent: ReservationIntent): string {
  const message = reservationErrorMessage(error);
  return intent.kind === "CHANGE" && error instanceof ApiError && ["RESERVATION_CONFLICT", "PERSONAL_OVERLAP", "DAILY_LIMIT_EXCEEDED", "ASSET_FAULTY", "DATE_NOT_OPEN"].includes(error.code)
    ? `${message} 이번 변경은 적용되지 않았으며 기존 예약·점유·사용 한도는 유지돼요.` : message;
}

// A transport/proxy failure can happen after commit. Preserve the exact request
// and key for an explicit retry; receiving no response is never proof of failure.
export function isUncertainReservationFailure(error: unknown): boolean {
  if (!(error instanceof ApiError)) return true;
  return error.code !== "OFFLINE" && (error.status === 0 || error.status >= 500 || error.status === 408 || error.code === "INVALID_RESPONSE");
}
