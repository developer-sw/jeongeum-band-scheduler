import type { QueryClient } from "@tanstack/react-query";
import { refreshDirectory } from "@/features/directory/queries";

export async function refreshLifecycle(client: QueryClient) {
  await Promise.all([
    refreshDirectory(client),
    client.invalidateQueries({ queryKey: ["notifications"] }),
    client.invalidateQueries({ queryKey: ["push"] }),
  ]);
}
