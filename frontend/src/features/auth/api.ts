import { ApiError, apiRequest } from "@/lib/api/client";
import type { Invitation, InvitationInput, IssuedInvitation, LoginInput, Member, RegisterInput } from "./types";

async function data<T>(request: ReturnType<typeof apiRequest<T>>): Promise<T> {
  const response = await request;
  if (!response) throw new ApiError(200, "INVALID_RESPONSE", "응답을 확인하지 못했어요. 다시 시도해 주세요.");
  return response.data;
}

// Credentials and issued tokens use direct, transient requests, never Query mutation variables/results.
export const login = (input: LoginInput, signal?: AbortSignal) => data(apiRequest<Member>("/api/v1/auth/login", { method: "POST", body: input, signal }));
export const register = (input: RegisterInput, signal?: AbortSignal) => data(apiRequest<Member>("/api/v1/auth/register", { method: "POST", body: input, signal }));
export const inspectInvitation = (token: string, signal?: AbortSignal) => data(apiRequest<{ expiresAt: string }>("/api/v1/auth/invitations/inspect", { method: "POST", body: { token }, signal }));
export const logout = (signal?: AbortSignal) => apiRequest("/api/v1/auth/logout", { method: "POST", signal });
export const getMember = (signal?: AbortSignal) => data(apiRequest<Member>("/api/v1/auth/me", { signal }));
export const issueInvitation = (input: InvitationInput, signal?: AbortSignal) => data(apiRequest<IssuedInvitation>("/api/v1/invitations", { method: "POST", body: input, signal }));
export const getInvitations = (cursor?: string | null, signal?: AbortSignal) => apiRequest<{ items: Invitation[] }>(`/api/v1/invitations${cursor ? `?cursor=${encodeURIComponent(cursor)}` : ""}`, { signal });
export const revokeInvitation = (id: string, signal?: AbortSignal) => apiRequest(`/api/v1/invitations/${encodeURIComponent(id)}`, { method: "DELETE", signal });
