import { infiniteQueryOptions, type QueryClient } from "@tanstack/react-query";
import { memberQueryKey } from "@/features/auth/session-cache";
import { getNotifications } from "./api";
export const notificationsQuery = (scope: string, unreadOnly = false) => infiniteQueryOptions({ queryKey: ["notifications", scope, "list", unreadOnly], initialPageParam: null as string | null, queryFn: ({ signal, pageParam }) => getNotifications({ unreadOnly, beforeId: pageParam ?? undefined }, signal), getNextPageParam: (page) => page.nextBeforeId ?? undefined, staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false });
export async function refreshNotifications(client: QueryClient) { await Promise.all([client.invalidateQueries({ queryKey: ["notifications"] }), client.invalidateQueries({ queryKey: memberQueryKey })]); }
