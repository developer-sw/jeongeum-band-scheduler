import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { memberQueryKey } from "@/features/auth/session-cache";
import { getAssignments } from "./api";

export const assignmentsQuery = (scope: string) => queryOptions({ queryKey: ["admin", "recurring", scope], queryFn: ({ signal }) => getAssignments(signal), staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false });
export async function refreshAssignments(client: QueryClient) {
  await Promise.all([client.invalidateQueries({ queryKey: ["admin", "recurring"] }), client.invalidateQueries({ queryKey: ["reservations"] }), client.invalidateQueries({ queryKey: memberQueryKey })]);
}
