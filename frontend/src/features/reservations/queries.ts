import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { memberQueryKey } from "@/features/auth/session-cache";
import { getMyReservations, getReservation, getSchedule } from "./api";

export const scheduleQuery = (scope: string) => queryOptions({ queryKey: ["reservations", scope, "schedule"], queryFn: ({ signal }) => getSchedule(signal), staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false });
export const myReservationsQuery = (scope: string, from: string, to: string) => queryOptions({ queryKey: ["reservations", scope, "mine", from, to], queryFn: ({ signal }) => getMyReservations(from, to, signal), staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false });
export const reservationQuery = (scope: string, id: string) => queryOptions({ queryKey: ["reservations", scope, "detail", id], queryFn: ({ signal }) => getReservation(id, signal), staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false });
export async function refreshReservations(client: QueryClient) {
  await Promise.all([client.invalidateQueries({ queryKey: ["reservations"] }), client.invalidateQueries({ queryKey: ["exchanges"] }), client.invalidateQueries({ queryKey: memberQueryKey })]);
}
