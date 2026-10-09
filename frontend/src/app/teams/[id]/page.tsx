"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { SessionGate, secondaryButton } from "@/components/auth-ui";
import { QueryState } from "@/components/directory-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope, refreshDirectory, teamQuery } from "@/features/directory/queries";
import { TeamDetailView } from "@/features/directory/team-detail";

function TeamDetail({ id }: { id: string }) {
  const { member } = useSession(); const client = useQueryClient(); const scope = directoryScope(member!);
  const query = useQuery(teamQuery(scope, id));
  return <div className="space-y-6"><div className="flex flex-wrap gap-3"><Link href="/teams" className={secondaryButton}>전체 팀으로</Link><button type="button" className={secondaryButton} onClick={() => void refreshDirectory(client)}>목록 새로 불러오기</button></div><QueryState pending={query.isPending} error={query.error} retry={() => void query.refetch()} />{!query.error && query.data && <TeamDetailView key={`${scope}:${id}`} detail={query.data} />}</div>;
}

export default function TeamPage() {
  const params = useParams<{ id: string }>();
  return <AppShell><SessionGate><TeamDetail id={params.id} /></SessionGate></AppShell>;
}
