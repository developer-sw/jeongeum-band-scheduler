"use client";

import { useQuery } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";

export interface HealthStatus {
  status: "UP";
}

export function useHealthQuery() {
  return useQuery({
    queryKey: ["health"],
    queryFn: async ({ signal }) => {
      const result = await apiRequest<HealthStatus>("/api/v1/health", { signal });
      if (!result) throw new Error("Health endpoint returned no content.");
      return result;
    },
    retry: false,
  });
}
