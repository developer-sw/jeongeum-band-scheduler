"use client";

import Link from "next/link";
import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { SessionGate, secondaryButton } from "@/components/auth-ui";
import { QueryState } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope, refreshDirectory, teamQuery } from "@/features/directory/queries";
import { TeamDetailView } from "@/features/directory/team-detail";
import { ApiError } from "@/lib/api/client";
import { LifecycleConfirmation, lifecycleResultMessage } from "@/features/lifecycle/confirmation";
import { lifecycleErrorMessage } from "@/features/lifecycle/errors";
import { lifecycleIntent, lifecycleTargetChanged } from "@/features/lifecycle/input";
import { refreshLifecycle } from "@/features/lifecycle/queries";
import { adminTeamPlaceholder, teamLifecycleIdentity, teamReadDenied } from "@/features/lifecycle/team-state";
import type { LifecycleTarget } from "@/features/lifecycle/types";
import { useLifecycleAction } from "@/features/lifecycle/use-lifecycle-action";

function TeamDetail({ id }: { id: string }) {
  const { member } = useSession(); const client = useQueryClient(); const scope = directoryScope(member!);
  const lifecycle = useLifecycleAction();
  const [target, setTarget] = useState<LifecycleTarget | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const query = useQuery({ ...teamQuery(scope, id), placeholderData: (previous, previousQuery) => adminTeamPlaceholder(previous, previousQuery?.queryKey, member!, id) });
  const admin = member!.globalRole === "GLOBAL_ADMIN";
  const readError = Boolean(query.error) || query.isPlaceholderData;
  async function disband(reason = "", retry = false) {
    if (!target || !admin || teamReadDenied(query.error)) return;
    const result = retry ? await lifecycle.retry() : await lifecycle.run(lifecycleIntent(target, reason));
    if (result) { setTarget(null); setNotice(lifecycleResultMessage(result)); }
    await refreshLifecycle(client);
  }
  // Keep a captured disband request mounted during a temporary read failure. Never retain it after access/not-found denial.
  const cachedAdminRead = member!.globalRole === "GLOBAL_ADMIN" && query.error
    && (!(query.error instanceof ApiError) || query.error.status === 0 || query.error.status >= 500);
  return <div className="space-y-6"><div className="flex flex-wrap gap-3"><Link href="/teams" className={secondaryButton}>전체 팀으로</Link><button type="button" className={secondaryButton} onClick={() => void refreshDirectory(client)}>목록 새로 불러오기</button></div><QueryState pending={query.isPending} error={query.error} retry={() => void query.refetch()} />
    {(!query.error || cachedAdminRead) && query.data && <TeamDetailView key={teamLifecycleIdentity(member!, id)} detail={query.data} readError={readError} lifecycle={{ open: target !== null, notice, onStart: (team) => { if (!admin || readError || team.status !== "ACTIVE") return; setNotice(null); lifecycle.clearFailure(); setTarget({ kind: "DISBAND", id: team.id, name: team.name, version: team.version }); } }} />}
    {target && admin && !teamReadDenied(query.error) && <LifecycleConfirmation target={target} stale={lifecycleTargetChanged(target, query.data?.team.version, !readError && query.data?.team.status === "ACTIVE" && query.data.team.id === target.id)} pending={lifecycle.pending} uncertain={lifecycle.uncertain} error={lifecycle.failure ? lifecycleErrorMessage(lifecycle.failure) : null} onConfirm={(reason) => void disband(reason)} onRetry={() => void disband("", true)} onCancel={() => { setTarget(null); lifecycle.clearFailure(); }} />}
  </div>;
}

function ScopedTeamDetail({ id }: { id: string }) {
  const { member } = useSession();
  return <TeamDetail key={teamLifecycleIdentity(member!, id)} id={id} />;
}

export default function TeamPage() {
  const params = useParams<{ id: string }>();
  return <AppShell><SessionGate><ScopedTeamDetail id={params.id} /></SessionGate></AppShell>;
}
