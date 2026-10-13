import { infiniteQueryOptions, queryOptions, type QueryClient } from "@tanstack/react-query";
import { memberQueryKey } from "@/features/auth/session-cache";
import { getInstrumentFaults, getInstruments } from "./api";
import { instrumentCodes } from "./types";

const options = { staleTime: 0, retry: false, refetchInterval: 20_000, refetchIntervalInBackground: false } as const;
export const instrumentsQuery = (scope: string) => queryOptions({ queryKey: ["admin", "instrument-faults", scope, "instruments"], queryFn: ({ signal }) => getInstruments(signal), ...options });
// Old open incidents may fall outside the latest global history page. Read the latest for each of six instruments.
export const currentFaultsQuery = (scope: string) => queryOptions({ queryKey: ["admin", "instrument-faults", scope, "current"], queryFn: async ({ signal }) => {
  const pages = await Promise.all(instrumentCodes.map((instrumentCode) => getInstrumentFaults({ instrumentCode, limit: 1 }, signal)));
  return pages.flatMap((page) => page.incidents.filter((incident) => incident.status === "OPEN"));
}, ...options });
export const faultHistoryQuery = (scope: string, instrumentCode = "") => infiniteQueryOptions({
  queryKey: ["admin", "instrument-faults", scope, "history", instrumentCode],
  initialPageParam: null as string | null,
  queryFn: ({ signal, pageParam }) => getInstrumentFaults({ instrumentCode, beforeId: pageParam ?? undefined }, signal),
  getNextPageParam: (page) => page.nextBeforeId ?? undefined,
  ...options,
});
export async function refreshInstrumentFaults(client: QueryClient) {
  await Promise.all([
    client.invalidateQueries({ queryKey: ["admin", "instrument-faults"] }),
    client.invalidateQueries({ queryKey: ["reservations"] }),
    client.invalidateQueries({ queryKey: ["exchanges"] }),
    client.invalidateQueries({ queryKey: ["admin", "recurring"] }),
    client.invalidateQueries({ queryKey: memberQueryKey }),
  ]);
}
