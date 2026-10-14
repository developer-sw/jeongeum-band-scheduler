"use client";

import { createContext, useContext, useEffect, useRef } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { onApiAccessError, resetApiSession } from "@/lib/api/client";
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
  const previousIdentity = useRef<string | null | undefined>(undefined);
  function recordIdentity(member: Member | null) {
    const identity = member?.status === "ACTIVE" ? member.id : null;
    if (previousIdentity.current !== undefined && previousIdentity.current !== identity) resetApiSession();
    previousIdentity.current = identity;
  }
  const query = useQuery({
    queryKey: memberQueryKey,
    queryFn: async ({ signal }) => {
      try { const member = await getMember(signal); recordIdentity(member); return member; }
      catch (error) { if (invalidatesSession(error)) { recordIdentity(null); return null; } throw error; }
    },
    staleTime: 0,
    retry: false,
    networkMode: "always",
  });

  useEffect(() => onApiAccessError((error) => {
    if (invalidatesSession(error)) { resetApiSession(); previousIdentity.current = null; void replaceSessionCache(client, null); }
    else if (error.code === "FORBIDDEN") {
      client.removeQueries({ queryKey: ["admin"] });
      client.removeQueries({ queryKey: ["directory"] });
      client.removeQueries({ queryKey: ["reservations"] });
      client.removeQueries({ queryKey: ["exchanges"] });
      client.removeQueries({ queryKey: ["notices"] });
      client.removeQueries({ queryKey: ["notifications"] });
      client.removeQueries({ queryKey: ["push"] });
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
      replace: (member) => { recordIdentity(member); return replaceSessionCache(client, member); },
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
