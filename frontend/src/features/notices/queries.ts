import { infiniteQueryOptions, queryOptions, type QueryClient } from "@tanstack/react-query";
import { memberQueryKey } from "@/features/auth/session-cache";
import { getNotice, getNotices } from "./api";
const options = { staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false } as const;
export const noticesQuery = (scope: string) => infiniteQueryOptions({ queryKey: ["notices", scope, "list"], initialPageParam: null as string | null, queryFn: ({ pageParam, signal }) => getNotices(pageParam ?? undefined, signal), getNextPageParam: (page) => page.nextCursor ?? undefined, ...options });
export const noticeQuery = (scope: string, id: string) => queryOptions({ queryKey: ["notices", scope, "detail", id], queryFn: ({ signal }) => getNotice(id, signal), ...options });
export async function refreshNotices(client: QueryClient) { await Promise.all([client.invalidateQueries({ queryKey: ["notices"] }), client.invalidateQueries({ queryKey: ["notifications"] }), client.invalidateQueries({ queryKey: memberQueryKey })]); }
