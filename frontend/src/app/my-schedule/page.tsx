"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { AppShell } from "@/components/app-shell";
import { Field, FormError, SessionGate, inputClass, secondaryButton } from "@/components/auth-ui";
import { useSession } from "@/features/auth/session-provider";
import type { Member } from "@/features/auth/types";
import { directoryScope } from "@/features/directory/queries";
import { myTeamSchedule } from "@/features/my-schedule/schedule";
import { MyTeamScheduleList } from "@/features/my-schedule/view";
import { reservationErrorMessage } from "@/features/reservations/errors";
import { myReservationsQuery, refreshReservations, scheduleQuery } from "@/features/reservations/queries";
import { dateLabel, seoulDate } from "@/features/reservations/time";
import { useServerClock } from "@/features/reservations/use-server-clock";

function MyScheduleBoard({ member, scope }: { member: Member; scope: string }) {
  const client = useQueryClient();
  const hasTeams = member.teamMemberships.length > 0;
  const schedule = useQuery({ ...scheduleQuery(scope), enabled: hasTeams });
  const now = useServerClock(schedule.data);
  const today = schedule.data && now ? seoulDate(now) : schedule.data?.today ?? "";
  const through = schedule.data?.bookableThrough ?? "";
  const reservations = useQuery({ ...myReservationsQuery(scope, today, through), enabled: hasTeams && Boolean(schedule.data && today && through && today <= through) });
  const [selectedTeam, setSelectedTeam] = useState("");
  const failure = schedule.error ?? reservations.error;
  const pending = hasTeams && (schedule.isPending || reservations.isPending);
  const groups = schedule.data && reservations.data ? myTeamSchedule(member, schedule.data, reservations.data, selectedTeam, today) : [];
  const preparing = schedule.data?.days.filter((day) => day.openingStatus !== "OPEN" && day.date >= today).length ?? 0;

  useEffect(() => {
    if (schedule.data && today !== schedule.data.today) void client.invalidateQueries({ queryKey: ["reservations", scope, "schedule"] });
  }, [client, scope, schedule.data, today]);

  return <div className="space-y-7"><section><p className="text-sm font-semibold text-navy-soft">함께하는 공연조의 연습 시간</p><h1 className="mt-2 text-3xl font-extrabold">내 공연조 일정 모아보기</h1><p className="mt-3 text-sm leading-7 text-navy-soft">내가 소속된 정규조·추가조의 확정된 합주 일정을 날짜순으로 확인해요. 개인 예약과 취소·대체 이력은 합주실 예약 화면에서 확인할 수 있어요.</p>{schedule.data && <p className="mt-2 text-xs leading-6 text-navy-soft">한국 날짜 기준 {dateLabel(today)}부터 {dateLabel(through)}까지 · 일정은 20초마다 확인해요.</p>}<div className="mt-4 flex flex-wrap gap-3">{hasTeams && <button type="button" className={secondaryButton} disabled={schedule.isFetching || reservations.isFetching} onClick={() => void refreshReservations(client)}>내 공연조 일정 새로 확인</button>}<Link href="/reservations" className={secondaryButton}>합주실 예약</Link><Link href="/teams" className={secondaryButton}>팀·곡 목록</Link></div></section>
    {!hasTeams ? <p role="status" className="rounded-2xl bg-paper p-6 text-sm leading-7">아직 소속된 공연조가 없어요. 팀 구성과 계정 연결은 관리자에게 확인해 주세요.</p> : <><div className="max-w-lg"><Field id="my-schedule-team" label="공연조 선택"><select id="my-schedule-team" className={inputClass} value={selectedTeam} onChange={(event) => setSelectedTeam(event.target.value)}><option value="">내 공연조 전체</option>{member.teamMemberships.map((membership) => <option key={membership.teamId} value={membership.teamId}>{membership.teamName} · {membership.kind === "REGULAR" ? "정규조" : "추가조"}</option>)}</select></Field></div>
      {pending && <p role="status" className="py-6 text-navy-soft">공연조 일정을 확인하고 있어요.</p>}{failure && <div className="space-y-4"><FormError message={reservationErrorMessage(failure)} /><button type="button" className={secondaryButton} onClick={() => void refreshReservations(client)}>다시 불러오기</button></div>}
      {!pending && !failure && schedule.data && reservations.data && <>{preparing > 0 && <p role="status" className="rounded-xl border border-yellow bg-yellow/10 p-5 text-sm leading-7">{preparing}개 날짜는 고정 시간을 반영 중이에요. 해당 날짜는 준비가 끝난 뒤 일정에 표시해요.</p>}{groups.length ? <MyTeamScheduleList days={groups} now={now} /> : <p role="status" className="rounded-2xl bg-paper p-6 text-sm leading-7">{selectedTeam ? "선택한 공연조는" : "내 공연조는"} 열린 기간에 확정된 합주 일정이 없어요.</p>}</>}
    </>}
  </div>;
}

function MyScheduleWorkspace() { const { member } = useSession(); const scope = directoryScope(member!); return <MyScheduleBoard key={scope} member={member!} scope={scope} />; }
export default function MySchedulePage() { return <AppShell><SessionGate><MyScheduleWorkspace /></SessionGate></AppShell>; }
