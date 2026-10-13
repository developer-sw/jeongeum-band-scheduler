import { ApiError } from "@/lib/api/client";
import { authErrorMessage } from "@/features/auth/errors";

const messages: Record<string, string> = {
  VERSION_CONFLICT: "악기 상태 또는 고장 이력이 먼저 변경됐어요. 최신 상태를 확인한 뒤 다시 선택해 주세요.",
  INSTRUMENT_ALREADY_FAULTY: "이미 고장으로 등록된 악기예요. 최신 열린 이력을 확인해 주세요.",
  INSTRUMENT_FAULT_RESOLVED: "이미 복구된 고장 이력이에요. 최신 상태를 확인해 주세요.",
  INSTRUMENT_FAULT_NOT_FOUND: "해당 고장 이력을 찾을 수 없어요. 최신 목록을 확인해 주세요.",
  INVALID_INSTRUMENT: "목록에서 관리할 악기를 선택해 주세요.",
  INSTRUMENT_FAULT_TEMPORARILY_UNAVAILABLE: "악기 상태 확인이 지연되고 있어요. 같은 요청 결과 다시 확인으로 처리 여부를 확인해 주세요.",
  RESERVATION_TEMPORARILY_UNAVAILABLE: "악기 상태 확인이 지연되고 있어요. 같은 요청 결과 다시 확인으로 처리 여부를 확인해 주세요.",
  IDEMPOTENCY_KEY_REUSED: "이전 요청과 내용이 달라 처리하지 못했어요. 최신 상태를 확인한 뒤 다시 선택해 주세요.",
};
export const faultErrorMessage = (error: unknown) => error instanceof ApiError && messages[error.code] ? messages[error.code] : authErrorMessage(error);
export function staleFaultReloadNotice(error: unknown, uncertain: boolean): string | null {
  return !uncertain && error instanceof ApiError && ["VERSION_CONFLICT", "INSTRUMENT_ALREADY_FAULTY", "INSTRUMENT_FAULT_RESOLVED", "INSTRUMENT_FAULT_NOT_FOUND"].includes(error.code)
    ? "이전 처리 확인을 닫았어요. 최신 악기 상태와 이력을 확인한 뒤 등록 또는 복구를 다시 선택해 주세요." : null;
}
