import type { Member } from "@/features/auth/types";
import { phaseAt, seoulDate } from "./time";
import type { ChangeReservationInput, InstrumentCode, Quota, ReservationDetail, ScheduleBlock, ScheduleDay, ScheduleSnapshot } from "./types";

export function changeUnavailableReason(reservation: ReservationDetail, now: number, member?: Member): string | null {
  if (reservation.status === "CANCELED") return "취소된 예약은 변경할 수 없어요. 예약 이력에서 취소 상태를 확인해 주세요.";
  if (reservation.status === "REPLACED") return "변경 전 예약 이력은 다시 변경할 수 없어요. 연결된 후속 예약의 현재 상태를 확인해 주세요.";
  const phase = phaseAt(reservation.startsAt, reservation.endsAt, now);
  if (phase === "ENDED") return "종료된 예약은 이용 이력으로 보존해요. 시작 전 예약만 변경할 수 있어요.";
  if (phase === "IN_PROGRESS") return "이미 시작한 예약은 변경할 수 없어요. 시작 전 예약만 변경할 수 있어요.";
  if (member && member.globalRole !== "GLOBAL_ADMIN") {
    const membership = member.teamMemberships.find((item) => item.teamId === reservation.teamId);
    if (reservation.subjectType === "PERSONAL" && reservation.ownerUserId !== member.id) return "다른 회원의 개인 예약은 전체 관리자만 변경할 수 있어요.";
    if (reservation.subjectType === "TEAM" && !membership) return "현재 이 팀에 소속되어 있지 않아 변경할 수 없어요. 소속 정보를 새로 확인해 주세요.";
    if (reservation.subjectType === "TEAM" && reservation.creatorUserId !== member.id && membership?.role !== "LEADER") return "다른 팀원이 만든 예약은 해당 팀장·전체 관리자만 변경할 수 있어요.";
  }
  return reservation.canChange ? null : "서버가 현재 예약의 변경 권한을 허용하지 않았어요. 활성 회원·팀 상태와 현재 소속·역할을 새로 확인해 주세요.";
}

export function canChangeReservation(reservation: ReservationDetail, now: number): boolean {
  return changeUnavailableReason(reservation, now) === null;
}

export function isUnchangedReservation(reservation: ReservationDetail, date: string, blockCode: string, instruments: InstrumentCode[]): boolean {
  return reservation.date === date && reservation.blockCode === blockCode && (reservation.subjectType === "TEAM"
    || reservation.instrumentCodes.length === instruments.length && reservation.instrumentCodes.every((code) => instruments.includes(code)));
}

export function changeInstrumentLimit(reservation: ReservationDetail, block: ScheduleBlock): 1 | 2 {
  return block.durationMinutes === 90 && reservation.dualAssetException ? 2 : 1;
}

// The schedule's personal quota and isMine flags belong to the signed-in actor.
// A global admin editing someone else's reservation must not use them as owner data.
export function changeQuota(member: Member, reservation: ReservationDetail, day: ScheduleDay): Quota | undefined {
  const quota = reservation.subjectType === "PERSONAL"
    ? reservation.ownerUserId === member.id ? day.personalQuota : undefined
    : member.teamMemberships.some((item) => item.teamId === reservation.teamId) ? day.teamQuotas.find((item) => item.teamId === reservation.teamId) : undefined;
  if (!quota) return undefined;
  const released = day.date === reservation.date && !reservation.quotaExempt ? reservation.quotaMinutes : 0;
  return { usedMinutes: Math.max(0, quota.usedMinutes - released), remainingMinutes: quota.remainingMinutes + released };
}

export function changeInput(member: Member, reservation: ReservationDetail, schedule: ScheduleSnapshot, date: string, blockCode: string, instruments: InstrumentCode[], now: number): ChangeReservationInput | string {
  const sourceReason = changeUnavailableReason(reservation, now, member);
  if (sourceReason) return sourceReason;
  const day = schedule.days.find((item) => item.date === date);
  const block = day?.blocks.find((item) => item.blockCode === blockCode);
  if (!day || date < seoulDate(now) || date > schedule.bookableThrough || day.openingStatus !== "OPEN") return "지금 개방된 예약 날짜를 선택해 주세요. 고정 배정 반영 중인 날짜는 변경할 수 없어요.";
  if (!block || phaseAt(block.startsAt, block.endsAt, now) === "ENDED") return "아직 종료되지 않은 고정 시간 블록을 선택해 주세요.";
  if (reservation.subjectType === "PERSONAL" && (instruments.length < 1 || instruments.length > changeInstrumentLimit(reservation, block)
    || new Set(instruments).size !== instruments.length || instruments.some((code) => !schedule.instruments.some((item) => item.code === code)))) {
    return changeInstrumentLimit(reservation, block) === 2 ? "개인 악기는 서로 다른 종류를 최대 두 개 선택해 주세요." : "이 예약은 개인 악기 한 종류를 선택해야 해요.";
  }
  const input: ChangeReservationInput = { expectedVersion: reservation.version, date, blockCode: block.blockCode,
    ...(reservation.subjectType === "PERSONAL" ? { instrumentCodes: [...instruments] } : {}) };
  // A no-op preserves the original exemption and existing faulty-asset reservation.
  if (isUnchangedReservation(reservation, date, blockCode, instruments)) return input;
  const others = block.reservations.filter((item) => item.id !== reservation.id && item.status === "CONFIRMED");
  if (others.some((item) => item.subjectType === "TEAM") || reservation.subjectType === "TEAM" && others.length) return "다른 예약이 점유한 시간에는 변경할 수 없어요. 최신 시간표에서 다시 선택해 주세요.";
  if (reservation.subjectType === "PERSONAL") {
    if (instruments.some((code) => block.faultyInstrumentCodes.includes(code) || schedule.instruments.some((item) => item.code === code && item.status === "FAULTY"))) return "고장 악기로 새로 배치할 수 없어요. 다른 악기를 선택해 주세요.";
    if (others.some((item) => item.instrumentCodes.some((code) => instruments.includes(code)))) return "선택한 악기를 다른 예약이 사용해요. 다른 악기나 시간을 선택해 주세요.";
    if (reservation.ownerUserId === member.id && others.some((item) => item.subjectType === "PERSONAL" && item.isMine)) return "이미 같은 시간에 본인의 다른 개인 예약이 있어요.";
  }
  const quota = changeQuota(member, reservation, day);
  if (block.durationMinutes === 60 && quota && quota.remainingMinutes < 60) return "변경 후 이 개인 또는 팀의 하루 60분 블록 한도를 초과해요. 다른 날짜나 90분 블록을 선택해 주세요.";
  return input;
}
