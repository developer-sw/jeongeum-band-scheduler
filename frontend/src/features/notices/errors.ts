import { ApiError } from "@/lib/api/client";
export function noticeErrorMessage(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.code === "OFFLINE") return "연결을 확인한 뒤 직접 다시 시도해 주세요. 변경 요청은 자동으로 보내지 않아요.";
    if (error.status === 401 || error.code === "INACTIVE_USER") return "현재 계정으로 로그인한 뒤 다시 확인해 주세요.";
    if (error.code === "CSRF_INVALID") return "요청의 보안 확인을 완료하지 못했어요. 직접 다시 시도하면 보안 정보를 새로 확인해요. 이전 결과를 확인 중이라면 같은 요청 결과 다시 확인을 눌러 주세요.";
    if (error.status === 403) return "현재 계정에는 이 공지를 관리할 권한이 없어요. 전체 관리자 권한을 다시 확인해 주세요.";
    if (error.status === 404 || error.code === "NOTICE_DELETED") return "삭제됐거나 확인할 수 없는 공지예요. 공지 목록을 새로 확인해 주세요.";
    if (error.code === "VERSION_CONFLICT") return "다른 관리자가 먼저 변경했어요. 최신 내용을 확인한 뒤 다시 수정해 주세요.";
    if (error.code === "IDEMPOTENCY_KEY_REUSED") return "이전 요청과 내용이 달라요. 최신 공지 상태를 확인한 뒤 다시 진행해 주세요.";
    if (error.status === 400) return "제목과 본문의 길이, 입력 내용을 확인해 주세요.";
    if (error.code === "LOCK_TIMEOUT" || error.code === "LOCK_ACQUISITION_TIMEOUT") return "다른 작업이 처리 중이에요. 잠시 후 같은 요청의 결과를 다시 확인해 주세요.";
  }
  return "공지 요청 결과를 확인하지 못했어요. 연결과 최신 공지 상태를 확인해 주세요.";
}
