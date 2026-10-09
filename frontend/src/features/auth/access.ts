import { ApiError } from "@/lib/api/client";
import type { Member } from "./types";

export function invalidatesSession(error: unknown): boolean {
  return error instanceof ApiError && ((error.status === 401 && error.code !== "INVALID_CREDENTIALS") || error.code === "INACTIVE_USER");
}

export function memberAccess(member: Member | null | undefined, admin = false): "LOGIN_REQUIRED" | "FORBIDDEN" | "ALLOWED" {
  if (!member || member.status !== "ACTIVE") return "LOGIN_REQUIRED";
  if (admin && member.globalRole !== "GLOBAL_ADMIN") return "FORBIDDEN";
  return "ALLOWED";
}
