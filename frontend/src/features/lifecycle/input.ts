import type { AdminMember, Team } from "@/features/directory/types";
import type { LifecycleIntent, LifecycleTarget } from "./types";

export function lifecycleReasonError(reason: string) {
  return reason.trim().length > 500 ? "처리 사유는 500자 이내로 입력해 주세요." : null;
}
export function lifecycleIntent(target: LifecycleTarget, reason: string): LifecycleIntent {
  const normalized = reason.trim();
  return { kind: target.kind, id: target.id, input: { expectedVersion: target.version, ...(normalized ? { reason: normalized } : {}) } };
}
export function withdrawalBlock(account: AdminMember, accounts: AdminMember[]): string | null {
  if (account.status === "WITHDRAWN") return "이미 탈퇴 처리된 계정이에요.";
  if (account.status === "ACTIVE" && account.globalRole === "GLOBAL_ADMIN"
    && accounts.filter((item) => item.status === "ACTIVE" && item.globalRole === "GLOBAL_ADMIN").length <= 1) {
    return "마지막 활성 전체 관리자는 탈퇴할 수 없어요. 다른 활성 계정에 관리자 권한을 먼저 부여해 주세요.";
  }
  return null;
}
export function disbandBlock(team: Team): string | null { return team.status === "DISBANDED" ? "이미 해체된 팀이에요." : null; }
export function lifecycleTargetChanged(target: LifecycleTarget, currentVersion?: number, allowed = true) {
  return !allowed || currentVersion === undefined || currentVersion !== target.version;
}
