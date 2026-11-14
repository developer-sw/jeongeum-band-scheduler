"use client";

import { useState } from "react";
import type { Member } from "@/features/auth/types";
import { dateLabel } from "@/features/reservations/time";
import type { ScheduleDay } from "@/features/reservations/types";
import { calendarWeekdays, inBookingRange, monthCells, monthLabel, moveMonth, weekdays, weekDates } from "./calendar";
import { CalendarDaySummary } from "./calendar-day-summary-view";

interface CalendarProps {
  selected: string; today: string; through: string; days: ScheduleDay[]; disabled: boolean;
  onSelect: (date: string) => void; onUnavailable: () => void;
  member?: Pick<Member, "teamMemberships">; stale?: boolean; refreshing?: boolean;
}

function CalendarIcon() {
  return <svg className="diary-calendar-icon" width="20" height="20" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <rect x="2" y="2" width="20" height="20" rx="2" fill="#ece8ff" />
    <path d="M4 2h16a2 2 0 0 1 2 2v4H2V4a2 2 0 0 1 2-2Z" fill="#66bbff" />
    <path d="M4 2h16a2 2 0 0 1 2 2H2a2 2 0 0 1 2-2Z" fill="#399dff" />
    <path d="M5 11h3v2H5zm5 0h3v2h-3zm5 0h3v2h-3zM5 15h3v2H5zm5 0h3v2h-3zm5 0h3v2h-3zM5 19h3v1H5zm5 0h3v1h-3zm5 0h3v1h-3z" fill="#b9a6e8" />
  </svg>;
}

function MonthCalendar({ selected, today, through, days, disabled, onSelect, onUnavailable, member, stale, refreshing }: CalendarProps) {
  const [month, setMonth] = useState(selected.slice(0, 7));
  const week = new Set(weekDates(selected));
  const available = new Set(days.map((day) => day.date));
  return <div className="diary-calendar">
    <p className="diary-eyebrow">MONTHLY NOTE</p>
    <div className="diary-calendar-heading"><h2><CalendarIcon />{monthLabel(month)}</h2><div className="diary-month-buttons">
      <button type="button" aria-label="이전 달" onClick={() => setMonth(moveMonth(month, -1))}>‹</button>
      <button type="button" aria-label="다음 달" onClick={() => setMonth(moveMonth(month, 1))}>›</button>
    </div></div>
    <div className="diary-calendar-grid" aria-hidden="true">{calendarWeekdays.map((day) => <span key={day} className="diary-weekday">{day}</span>)}</div>
    <div className="diary-calendar-grid">{monthCells(month).map((date) => {
      const inside = inBookingRange(date, today, through) && available.has(date);
      const preparing = days.find((day) => day.date === date)?.openingStatus === "PREPARING";
      return <button key={date} type="button" disabled={disabled} aria-pressed={date === selected}
        aria-current={date === today ? "date" : undefined}
        aria-label={`${dateLabel(date)}${date === today ? " · 오늘" : ""}${!inside ? " · 예약 기간 밖" : preparing ? " · 고정 배정 반영 중" : " · 예약 가능 날짜"}`}
        className={`diary-calendar-date ${date.slice(0, 7) !== month ? "other-month" : ""} ${week.has(date) ? "selected-week" : ""} ${inside ? "in-range" : "out-of-range"} ${date === selected ? "selected" : ""} ${date === today ? "today" : ""}`}
        onClick={() => inside ? onSelect(date) : onUnavailable()}>{Number(date.slice(8))}</button>;
    })}</div>
    <button type="button" className="diary-calendar-today" disabled={disabled} onClick={() => { setMonth(today.slice(0, 7)); onSelect(today); }}>오늘로 돌아가기</button>
    <div className="diary-calendar-key"><span>● 오늘</span><span>골드: 선택 날짜</span><span>옅은 골드: 선택 주</span><span>밑줄: 예약 기간</span></div>
    <CalendarDaySummary selected={selected} day={days.find((day) => day.date === selected)} member={member} stale={stale} refreshing={refreshing} />
  </div>;
}

export function CalendarSidebar(props: CalendarProps) {
  return <aside className="diary-calendar-sidebar diary-card"><MonthCalendar key={props.selected.slice(0, 7)} {...props} /><div className="diary-calendar-note"><span aria-hidden="true">♪</span><p>한 칸의 연습이 모여<br />우리의 무대가 돼요.</p></div></aside>;
}

export function CalendarPopup(props: CalendarProps) {
  return <details className="diary-calendar-popup"><summary>전체 달력 열기 <span aria-hidden="true">⌄</span></summary>
    <div className="diary-calendar-popover diary-card"><MonthCalendar key={props.selected.slice(0, 7)} {...props} /></div>
  </details>;
}

export function DateStrip({ days, selected, today, disabled, onSelect }: Omit<CalendarProps, "through" | "onUnavailable">) {
  return <div className="diary-date-strip" aria-label="예약 날짜 선택">{days.map((day) => <button key={day.date} type="button" disabled={disabled} aria-pressed={day.date === selected} aria-current={day.date === today ? "date" : undefined}
    aria-label={`${dateLabel(day.date)}${day.date === today ? " 오늘" : ""}${day.openingStatus === "PREPARING" ? " 준비 중" : ""}`}
    className={day.date === selected ? "selected" : ""} onClick={() => onSelect(day.date)}>
    <span>{day.date === today ? "오늘" : weekdays[(new Date(`${day.date}T00:00:00Z`).getUTCDay() + 6) % 7]}</span><strong>{Number(day.date.slice(8))}</strong><span>{day.openingStatus === "PREPARING" ? "준비 중" : `${Number(day.date.slice(5, 7))}월`}</span>
  </button>)}</div>;
}
