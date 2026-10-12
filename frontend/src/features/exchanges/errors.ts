import { ApiError } from "@/lib/api/client";
import { reservationErrorMessage } from "@/features/reservations/errors";

const messages: Record<string, string> = {
  INVALID_EXCHANGE_SHAPE: "이 조합은 교환할 수 없어요. 한 건끼리 또는 같은 날 연속 60분 두 건과 90분 한 건을 선택해 주세요. 두 악기는 60분 한 건으로 줄일 수 없어요.",
  EXCHANGE_ALLOCATION_REQUIRED: "두 악기 90분 예약의 예약자가 각 60분에 사용할 악기 순서를 먼저 저장해야 해요.",
  EXCHANGE_EXPIRED: "가장 이른 예약의 시작 시각이 지나 교환 요청이 만료됐어요. 기존 예약은 그대로예요.",
  EXCHANGE_INVALIDATED: "예약이나 요청자의 현재 권한이 바뀌어 이 교환은 무효화됐어요. 최신 예약을 확인해 주세요.",
  EXCHANGE_NOT_PENDING: "이미 처리된 교환 요청이에요. 최신 요청 상태를 확인해 주세요.",
  VERSION_CONFLICT: "예약 또는 교환 요청이 먼저 바뀌었어요. 최신 상세와 미리보기를 다시 확인해 주세요.",
};
export function exchangeErrorMessage(error: unknown): string {
  const message = error instanceof ApiError && messages[error.code] ? messages[error.code] : reservationErrorMessage(error);
  return error instanceof ApiError && ["RESERVATION_CONFLICT", "PERSONAL_OVERLAP", "DAILY_LIMIT_EXCEEDED", "ASSET_FAULTY", "DATE_NOT_OPEN"].includes(error.code)
    ? `${message} 교환은 적용되지 않았으며 양측 기존 예약·점유·사용 한도는 유지돼요.` : message;
}
