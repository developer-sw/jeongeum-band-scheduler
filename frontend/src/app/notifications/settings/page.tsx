"use client";
import { AppShell } from "@/components/app-shell";
import { SessionGate } from "@/components/auth-ui";
import { useSession } from "@/features/auth/session-provider";
import { directoryScope } from "@/features/directory/queries";
import { PushSettings } from "@/features/push/settings-view";
function Workspace() { const { member } = useSession(); const scope = directoryScope(member!); return <PushSettings key={scope} scope={scope} />; }
export default function PushSettingsPage() { return <AppShell><SessionGate><Workspace /></SessionGate></AppShell>; }
