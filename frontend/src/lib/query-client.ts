import { QueryClient } from "@tanstack/react-query";

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: false },
      // Run immediately so the API client rejects offline writes instead of queuing them.
      mutations: { retry: false, networkMode: "always" },
    },
  });
}
