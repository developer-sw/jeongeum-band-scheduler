import type { Member } from "@/features/auth/types";
import { dateLabel } from "@/features/reservations/time";
import type { ScheduleDay } from "@/features/reservations/types";
import { calendarReservationRows } from "./calendar-day-summary";

export function CalendarDaySummary({ selected, day, member, stale = false, refreshing = false }: {
  selected: string; day?: ScheduleDay; member?: Pick<Member, "teamMemberships">; stale?: boolean; refreshing?: boolean;
}) {
  const rows = day?.openingStatus === "OPEN" ? calendarReservationRows(day, member) : [];
  return <section className="diary-calendar-reservations" aria-label={`${dateLabel(selected)} 예약 요약`} aria-busy={refreshing}>
    <h3>{dateLabel(selected)} 예약</h3>
    {stale && <p className="diary-calendar-summary-status" role="status">마지막으로 확인한 예약이에요.</p>}
    {!day ? <p className="diary-calendar-summary-status">선택한 날짜의 예약을 확인하고 있어요.</p>
      : day.openingStatus !== "OPEN" ? <p className="diary-calendar-summary-status">고정 배정을 반영하고 있어요.</p>
      : !rows.length ? <p className="diary-calendar-summary-empty">예약된 시간이 없어요.</p>
      : <ul className="diary-calendar-reservation-list">{rows.map((row) => <li key={row.key} className={`diary-calendar-reservation${row.myTeam ? " my-team" : ""}`}>
        <span className="diary-calendar-reservation-time">{row.time}</span>
        <span className="diary-calendar-reservation-subject"><strong>{row.label}</strong>{row.fixed && <span className="diary-calendar-reservation-badge">고정</span>}{row.myTeam && <span className="diary-calendar-my-team">내 공연조</span>}</span>
      </li>)}</ul>}
  </section>;
}
