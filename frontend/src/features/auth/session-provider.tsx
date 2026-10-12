"use client";

import { createContext, useContext, useEffect } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { onApiAccessError } from "@/lib/api/client";
import { getMember } from "./api";
import { invalidatesSession } from "./access";
import { memberQueryKey, replaceSessionCache } from "./session-cache";
import type { Member } from "./types";
import { directoryScope, pruneDirectoryCache } from "@/features/directory/queries";

interface Session {
  member: Member | null;
  loading: boolean;
  error: unknown;
  refresh: () => Promise<unknown>;
  replace: (member: Member | null) => Promise<void>;
}

const SessionContext = createContext<Session | null>(null);

export function SessionProvider({ children }: { children: React.ReactNode }) {
  const client = useQueryClient();
  const query = useQuery({
    queryKey: memberQueryKey,
    queryFn: async ({ signal }) => {
      try { return await getMember(signal); }
      catch (error) { if (invalidatesSession(error)) return null; throw error; }
    },
    staleTime: 0,
    retry: false,
    networkMode: "always",
  });

  useEffect(() => onApiAccessError((error) => {
    if (invalidatesSession(error)) void replaceSessionCache(client, null);
    else if (error.code === "FORBIDDEN") {
      client.removeQueries({ queryKey: ["admin"] });
      client.removeQueries({ queryKey: ["directory"] });
      client.removeQueries({ queryKey: ["reservations"] });
      client.removeQueries({ queryKey: ["exchanges"] });
      void client.invalidateQueries({ queryKey: memberQueryKey });
    }
  }), [client]);

  const scope = query.data ? directoryScope(query.data) : null;
  useEffect(() => { void pruneDirectoryCache(client, query.data ?? null); }, [client, scope, query.data]);

  return (
    <SessionContext.Provider value={{
      member: query.data?.status === "ACTIVE" ? query.data : null,
      loading: query.isPending,
      error: query.error,
      refresh: query.refetch,
      replace: (member) => replaceSessionCache(client, member),
    }}>
      {children}
    </SessionContext.Provider>
  );
}

export function useSession() {
  const session = useContext(SessionContext);
  if (!session) throw new Error("SessionProvider is required.");
  return session;
}
