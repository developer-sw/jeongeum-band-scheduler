"use client";

import Image from "next/image";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import type { Member } from "@/features/auth/types";
import { myTeamSchedule } from "@/features/my-schedule/schedule";
import { myReservationsQuery } from "@/features/reservations/queries";
import { addDate, blockLabel, dateLabel } from "@/features/reservations/time";
import type { ScheduleSnapshot } from "@/features/reservations/types";

export function TeamSummary({ member, scope, schedule, today, now, highlight, onDate, disabled }: { member: Member; scope: string; schedule: ScheduleSnapshot; today: string; now: number; highlight: string; onDate: (date: string) => void; disabled: boolean }) {
  const hasTeams = member.teamMemberships.length > 0;
  const query = useQuery({ ...myReservationsQuery(scope, today, schedule.bookableThrough), enabled: hasTeams && today <= schedule.bookableThrough });
  const groups = query.data ? myTeamSchedule(member, schedule, query.data, highlight, today) : [];
  const next = groups.flatMap((group) => group.reservations).filter((reservation) => reservation.date <= addDate(today, 6) && Date.parse(reservation.endsAt) > now).slice(0, 3);
  return <section className="diary-summary diary-card" aria-labelledby="diary-summary-title">
    <p className="diary-eyebrow">OUR NEXT REHEARSAL</p><h2 id="diary-summary-title"><span aria-hidden="true">📌</span> 다가오는 7일 합주</h2><p className="diary-small">{dateLabel(today)} ~ {dateLabel(addDate(today, 6))}{highlight && " · 선택한 공연조"}</p>
    {!hasTeams ? <p className="diary-summary-empty">아직 연결된 공연조가 없어요.<br />개인 연습은 바로 예약할 수 있어요.</p>
      : query.isPending ? <p role="status" className="diary-summary-empty">우리 조 일정을 확인하고 있어요.</p>
      : query.isError ? <div className="diary-summary-empty"><p role="alert">공연조 일정을 불러오지 못했어요.</p><button type="button" className="diary-text-button" onClick={() => void query.refetch()}>다시 확인</button></div>
      : next.length ? <ul className="diary-summary-list">{next.map((reservation) => <li key={reservation.id}><button type="button" className="diary-summary-entry" disabled={disabled} onClick={() => onDate(reservation.date)} aria-label={`${dateLabel(reservation.date)} ${reservation.teamName} ${blockLabel(reservation)} 시간표 보기`}><span>{dateLabel(reservation.date)} <span className="diary-summary-tag">{reservation.originKind === "FIXED" ? "고정" : "일반"}</span></span><strong>{reservation.teamName}</strong><span>{blockLabel(reservation)}</span></button></li>)}</ul>
      : <p className="diary-summary-empty">다가오는 7일에 확정된 합주가 없어요.<br />같이 연습할 시간을 골라볼까요?</p>}
    <Link href="/my-schedule" className="diary-text-link diary-summary-all">내 공연조 일정 모두 보기 →</Link>
    <div className="diary-summary-bottom"><Image src="/guitar-mascot-v2.png" alt="" width={393} height={635} sizes="48px" className="diary-mascot" /><p className="diary-encouragement">오늘도 맹연습 화이팅! 소리 맞춰봐요 🎶</p></div>
  </section>;
}
