import type { Member } from "@/features/auth/types";
import type { TeamDetail } from "@/features/directory/types";
import { ApiError } from "@/lib/api/client";

// Membership changes keep the same admin operation alive; account, role or target changes end that scope.
export function teamLifecycleIdentity(member: Member, teamId: string) {
  return JSON.stringify([member.id, member.globalRole, teamId]);
}
export function teamReadDenied(error: unknown) {
  return error instanceof ApiError && [401, 403, 404].includes(error.status);
}
export function adminTeamPlaceholder(previous: TeamDetail | undefined, previousKey: readonly unknown[] | undefined, member: Member, teamId: string) {
  if (!previous || member.status !== "ACTIVE" || member.globalRole !== "GLOBAL_ADMIN" || previous.team.id !== teamId
    || previousKey?.[0] !== "directory" || previousKey?.[2] !== "team" || previousKey?.[3] !== teamId || typeof previousKey?.[1] !== "string") return undefined;
  try {
    const priorScope: unknown = JSON.parse(previousKey[1]);
    if (Array.isArray(priorScope) && priorScope[0] === member.id && priorScope[1] === "GLOBAL_ADMIN") return previous;
  } catch { /* Invalid scopes never carry private data forward. */ }
  return undefined;
}
