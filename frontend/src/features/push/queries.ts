import { queryOptions, type QueryClient } from "@tanstack/react-query";
import { getPushDevices, getPushPublicKey } from "./api";

export const pushPublicKeyQuery = (scope: string) => queryOptions({ queryKey: ["push", scope, "public-key"], queryFn: ({ signal }) => getPushPublicKey(signal), staleTime: 0, retry: false });
export const pushDevicesQuery = (scope: string) => queryOptions({ queryKey: ["push", scope, "devices"], queryFn: ({ signal }) => getPushDevices(signal), staleTime: 0, retry: false });
export const refreshPush = (client: QueryClient) => client.invalidateQueries({ queryKey: ["push"] });
