"use client";

import { Suspense } from "react";
import { useSearchParams } from "next/navigation";
import { AppShell } from "@/components/app-shell";
import { SessionGate } from "@/components/auth-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { ExchangeBoard } from "@/features/exchanges/view";

function Workspace() {
  const { member } = useSession(); const search = useSearchParams();
  const scope = directoryScope(member!); const initialReservationId = search.get("reservationId") ?? ""; const initialId = search.get("exchangeId") ?? "";
  return <ExchangeBoard key={`${scope}:${initialReservationId}:${initialId}`} member={member!} scope={scope} initialReservationId={initialReservationId} initialId={initialId} />;
}
export default function ExchangesPage() { return <AppShell><SessionGate><Suspense fallback={<p role="status">교환 화면을 준비하고 있어요.</p>}><Workspace /></Suspense></SessionGate></AppShell>; }
