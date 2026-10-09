import type { QueryClient } from "@tanstack/react-query";
import type { Member } from "./types";

export const memberQueryKey = ["auth", "me"] as const;

export async function replaceSessionCache(client: QueryClient, member: Member | null) {
  await client.cancelQueries();
  // Keep the observed auth query so the mounted session provider receives the replacement.
  client.removeQueries({ predicate: (query) => !(query.queryKey.length === 2 && query.queryKey[0] === "auth" && query.queryKey[1] === "me") });
  client.getMutationCache().clear();
  client.setQueryData(memberQueryKey, member);
}
