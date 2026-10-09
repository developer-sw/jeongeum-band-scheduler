import { ApiError } from "@/lib/api/client";

const messages: Record<string, string> = {
  INVALID_CREDENTIALS: "아이디 또는 비밀번호를 확인해 주세요.",
  AUTHENTICATION_REQUIRED: "다시 로그인해 주세요.",
  INACTIVE_USER: "현재 이용할 수 없는 계정이에요. 관리자에게 문의해 주세요.",
  FORBIDDEN: "이 작업을 처리할 권한이 없어요.",
  INVITATION_UNAVAILABLE: "사용했거나 만료·해지된 초대예요. 관리자에게 새 초대를 요청해 주세요.",
  INVITATION_ALREADY_USED: "이미 가입에 사용된 초대는 해지할 수 없어요.",
  LOGIN_ID_TAKEN: "이미 사용 중인 아이디예요. 다른 아이디로 다시 시도해 주세요.",
  TOO_MANY_REQUESTS: "요청이 많아요. 잠시 기다린 뒤 다시 시도해 주세요.",
  INVALID_INPUT: "입력 내용을 확인해 주세요.",
  CSRF_INVALID: "요청을 확인하지 못했어요. 다시 한 번 시도해 주세요.",
  OFFLINE: "오프라인에서는 요청할 수 없어요. 연결을 확인한 뒤 직접 다시 시도해 주세요.",
};

// Never render an arbitrary server message containing an echoed credential or invitation.
export function authErrorMessage(error: unknown): string {
  return error instanceof ApiError && messages[error.code] ? messages[error.code] : "요청을 완료하지 못했어요. 연결을 확인한 뒤 다시 시도해 주세요.";
}
