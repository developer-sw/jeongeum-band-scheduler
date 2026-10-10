import type { Member } from "@/features/auth/types";
import type { CreateReservationInput, InstrumentCode, ScheduleBlock, ScheduleDay } from "./types";

export function bookingInput(member: Member, day: ScheduleDay, block: ScheduleBlock, subject: string, instrument: string, now: number): CreateReservationInput | string {
  if (day.openingStatus !== "OPEN") return "고정 시간이 반영된 뒤 예약할 수 있어요. 시간표를 새로 확인해 주세요.";
  if (now >= Date.parse(block.endsAt)) return "이미 종료된 블록이에요. 다른 시간을 선택해 주세요.";
  if (subject === "PERSONAL") {
    if (!block.canReservePersonal) return "지금 이 블록에 개인 예약을 추가할 수 없어요. 점유·동시간 개인 예약·하루 한도를 확인해 주세요.";
    if (!block.availableInstrumentCodes.includes(instrument as InstrumentCode)) return "현재 예약 가능한 악기 한 종류를 선택해 주세요.";
    if (block.durationMinutes === 60 && day.personalQuota.remainingMinutes < 60) return "본인의 하루 60분 블록 한도를 모두 사용했어요.";
    return { subjectType: "PERSONAL", date: day.date, blockCode: block.blockCode, instrumentCodes: [instrument as InstrumentCode] };
  }
  if (!member.teamMemberships.some((membership) => membership.teamId === subject)) return "현재 소속된 팀을 선택해 주세요.";
  if (!block.canReserveTeam) return "다른 예약이 있는 블록에는 팀 합주를 예약할 수 없어요. 시간표를 다시 확인해 주세요.";
  const quota = day.teamQuotas.find((item) => item.teamId === subject);
  if (block.durationMinutes === 60 && (!quota || quota.remainingMinutes < 60)) return "선택한 팀의 하루 60분 블록 한도를 모두 사용했어요.";
  return { subjectType: "TEAM", date: day.date, blockCode: block.blockCode, teamId: subject };
}
