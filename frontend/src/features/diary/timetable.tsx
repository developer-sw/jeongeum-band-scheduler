"use client";

import type { Member } from "@/features/auth/types";
import { blockLabel, dateLabel } from "@/features/reservations/time";
import type { ScheduleBlock, ScheduleDay } from "@/features/reservations/types";
import { weekdays } from "./calendar";
import { slotView } from "./slot";

interface TimetableProps {
  days: ScheduleDay[]; week: string[]; selectedDate: string; selectedCode?: string;
  member: Member; now: number; highlight: string; disabled: boolean;
  onDate: (date: string) => void;
  onSelect: (date: string, block: ScheduleBlock, trigger: HTMLButtonElement) => void;
}

function SlotContents({ day, block, member, now, highlight, mobile = false }: {
  day: ScheduleDay; block: ScheduleBlock; member: Member; now: number; highlight: string; mobile?: boolean;
}) {
  const view = slotView(day, block, member, now, highlight);
  return <>
    {(mobile || view.team) && <span className="diary-slot-type">{view.typeLabel}</span>}<strong className="diary-slot-title">{view.title}</strong>
    {(view.myTeam || view.mine) && <span className="diary-slot-own">{view.myTeam ? "내 공연조" : "내 개인 예약"}</span>}
    {view.team?.quotaExempt && <span className="diary-slot-note">60분 한도 면제</span>}
    {(mobile || view.phase !== "FUTURE") && <span className="diary-slot-note">{view.timeStatus}</span>}
    {block.faultyInstrumentCodes.length > 0 && <span className="diary-slot-note">고장 {block.faultyInstrumentCodes.length}종</span>}
    {!view.team && day.openingStatus === "OPEN" && <span className="diary-slot-note">이용 가능 악기 {block.availableInstrumentCodes.length}/6</span>}
    <span className="diary-slot-action">{mobile ? `${view.actionLabel} →` : view.actionLabel}</span>
  </>;
}

export function Timetable(props: TimetableProps) {
  const { days, week, selectedDate, selectedCode, member, now, highlight, disabled, onDate, onSelect } = props;
  const reference = days.find((day) => day.date === selectedDate) ?? days[0];
  const selectedDay = days.find((day) => day.date === selectedDate);
  if (!reference) return null;
  return <>
    <div className="diary-week-table-wrap"><table className="diary-week-table" aria-label="월요일부터 일요일까지 주간 합주실 시간표">
      <thead><tr><th scope="col" className="diary-time-heading">시간</th>{week.map((date, index) => {
        const day = days.find((item) => item.date === date);
        return <th scope="col" key={date}><button type="button" disabled={!day || disabled} className={`diary-column-date ${date === selectedDate ? "selected" : ""}`} aria-pressed={date === selectedDate} onClick={() => onDate(date)} aria-label={`${dateLabel(date)} 선택`}><span>{weekdays[index]}</span><strong>{Number(date.slice(8))}</strong><span>{Number(date.slice(5, 7))}월</span></button></th>;
      })}</tr></thead>
      <tbody>{reference.blocks.map((row) => <tr key={row.blockCode} className={row.blockCode === "B07" ? "diary-evening-row" : ""}>
        <th scope="row"><span>{blockLabel(row).split("~")[0]}</span><span className="diary-time-end">~{blockLabel(row).split("~")[1]}</span><span className="diary-time-duration">{row.durationMinutes}분</span></th>
        {week.map((date) => {
          const day = days.find((item) => item.date === date);
          const block = day?.blocks.find((item) => item.blockCode === row.blockCode);
          if (!day || !block) return <td key={date}><div className="diary-slot-outside">기간 밖<span>예약·이력에서 확인</span></div></td>;
          const view = slotView(day, block, member, now, highlight);
          return <td key={date}><button type="button" disabled={disabled} data-diary-slot={`${date}_${block.blockCode}`} className={`diary-slot diary-slot-${view.kind} ${view.myTeam || view.mine ? "own" : ""} ${date === selectedDate && block.blockCode === selectedCode ? "chosen" : ""}`}
            aria-pressed={date === selectedDate && block.blockCode === selectedCode}
            aria-label={`${dateLabel(date)} ${blockLabel(block)} · ${view.title} · ${view.timeStatus} · ${view.actionLabel}`}
            onClick={(event) => onSelect(date, block, event.currentTarget)}><SlotContents day={day} block={block} member={member} now={now} highlight={highlight} /></button></td>;
        })}
      </tr>)}</tbody>
    </table></div>
    <div className="diary-day-list" aria-label="선택한 날짜의 하루 시간표"><h2>{dateLabel(selectedDate)} <span>하루 {selectedDay?.blocks.length ?? 0}개 블록</span></h2>
      {selectedDay?.openingStatus === "PREPARING" && <p className="diary-preparing-notice" role="status">고정 배정을 반영 중이에요. 완료되면 예약할 수 있어요.</p>}
      {selectedDay?.blocks.map((block) => {
        const view = slotView(selectedDay, block, member, now, highlight);
        return <button key={block.blockCode} type="button" disabled={disabled} data-diary-slot={`${selectedDate}_${block.blockCode}`} className={`diary-day-slot diary-slot-${view.kind} ${view.myTeam || view.mine ? "own" : ""} ${block.blockCode === selectedCode ? "chosen" : ""}`}
          aria-pressed={block.blockCode === selectedCode} onClick={(event) => onSelect(selectedDate, block, event.currentTarget)} aria-label={`${dateLabel(selectedDate)} ${blockLabel(block)} · ${view.title} · ${view.actionLabel}`}>
          <span className="diary-day-time"><strong>{blockLabel(block)}</strong><span>{block.durationMinutes}분 블록</span></span><span className="diary-day-description"><SlotContents day={selectedDay} block={block} member={member} now={now} highlight={highlight} mobile /></span>
        </button>;
      })}
    </div>
  </>;
}

export function TimetableLegend() {
  return <div className="diary-legend" aria-label="시간표 범례"><span><i className="diary-legend-fixed" aria-hidden="true" />고정 배정</span><span><i className="diary-legend-team" aria-hidden="true" />팀 예약</span><span><i className="diary-legend-personal" aria-hidden="true" />개인 공유</span><span><i className="diary-legend-own" aria-hidden="true" />내 공연조·개인</span><span><i className="diary-legend-free" aria-hidden="true" />빈 시간</span></div>;
}
