import { ApiError } from "@/lib/api/client";

export function lifecycleErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "OFFLINE") return "연결을 확인한 뒤 직접 다시 시도해 주세요. 탈퇴·해체 요청은 저장하거나 자동 실행하지 않아요.";
    if (error.status === 401 || error.code === "INACTIVE_USER") return "현재 계정으로 로그인한 뒤 최신 회원·팀 상태를 확인해 주세요. 탈퇴한 계정은 다시 처리할 수 없어요.";
    if (["CSRF_INVALID", "INVALID_CSRF_RESPONSE"].includes(error.code)) return "보안 확인을 완료하지 못했어요. 직접 다시 시도해 주세요. 이전 결과를 확인 중이라면 같은 요청 결과 다시 확인을 눌러 주세요.";
    if (error.status === 403) return "탈퇴·팀 해체는 현재 활성 전체 관리자만 처리할 수 있어요. 권한을 다시 확인해 주세요.";
    if (error.status === 404) return "확인할 수 없는 회원 또는 팀이에요. 목록을 새로 확인해 주세요.";
    if (error.code === "LAST_GLOBAL_ADMIN") return "마지막 활성 전체 관리자는 탈퇴할 수 없어요. 다른 활성 계정에 관리자 권한을 먼저 부여해 주세요.";
    if (error.code === "VERSION_CONFLICT") return "다른 관리자가 먼저 변경했어요. 창을 닫고 최신 상태를 확인한 뒤 다시 진행해 주세요.";
    if (["MEMBER_ALREADY_WITHDRAWN", "USER_ALREADY_WITHDRAWN", "TEAM_ALREADY_DISBANDED"].includes(error.code)) return "이미 탈퇴·해체 처리됐어요. 목록을 새로 확인해 주세요.";
    if (error.code === "IDEMPOTENCY_KEY_REUSED") return "이전 요청과 내용이 달라요. 최신 상태를 확인한 뒤 다시 진행해 주세요.";
    if (error.code === "DATA_CONFLICT") return "저장 충돌로 처리되지 않았어요. 최신 회원·팀 상태를 확인한 뒤 직접 다시 시도해 주세요.";
    if (["LOCK_TIMEOUT", "LOCK_ACQUISITION_TIMEOUT", "LIFECYCLE_TEMPORARILY_UNAVAILABLE"].includes(error.code)) return "다른 작업이 처리 중이에요. 잠시 후 같은 요청 결과 다시 확인을 눌러 주세요.";
    if (error.status === 400) return "대상과 버전, 사유 길이를 확인해 주세요.";
    if (error.status === 429) return "요청이 많아요. 잠시 후 직접 다시 시도해 주세요.";
  }
  return "처리 결과를 확인하지 못했어요. 실패로 단정하지 말고 같은 요청 결과 다시 확인을 눌러 주세요.";
}
