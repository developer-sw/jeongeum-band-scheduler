import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { refreshReservations } from "@/features/reservations/queries";
import { getExchange, getExchangeCandidates, getExchanges } from "./api";

const polling = { staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false } as const;
export const exchangesQuery = (scope: string) => queryOptions({ queryKey: ["exchanges", scope, "list"], queryFn: ({ signal }) => getExchanges(signal), ...polling });
export const exchangeQuery = (scope: string, id: string) => queryOptions({ queryKey: ["exchanges", scope, "detail", id], queryFn: ({ signal }) => getExchange(id, signal), ...polling });
export const exchangeCandidatesQuery = (scope: string, from: string, to: string) => queryOptions({ queryKey: ["exchanges", scope, "candidates", from, to], queryFn: ({ signal }) => getExchangeCandidates(from, to, signal), ...polling });
export const refreshExchanges = (client: QueryClient) => refreshReservations(client);
