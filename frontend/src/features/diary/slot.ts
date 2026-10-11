import type { Member } from "@/features/auth/types";
import { phaseAt, remainingMinutes } from "@/features/reservations/time";
import type { ScheduleBlock, ScheduleDay } from "@/features/reservations/types";

export function slotView(day: ScheduleDay, block: ScheduleBlock, member: Member, now: number, highlightedTeam = "") {
  const reservations = block.reservations.filter((reservation) => reservation.status === "CONFIRMED");
  const team = reservations.find((reservation) => reservation.subjectType === "TEAM");
  const used = new Set(reservations.filter((reservation) => reservation.subjectType === "PERSONAL").flatMap((reservation) => reservation.instrumentCodes)).size;
  const mine = reservations.some((reservation) => reservation.subjectType === "PERSONAL" && reservation.isMine);
  const myTeam = Boolean(team?.teamId && member.teamMemberships.some((membership) => membership.teamId === team.teamId)
    && (!highlightedTeam || team.teamId === highlightedTeam));
  const phase = phaseAt(block.startsAt, block.endsAt, now);
  const preparing = day.openingStatus !== "OPEN";
  const kind = preparing ? "preparing" : phase === "ENDED" ? "ended" : team ? team.originKind === "FIXED" ? "fixed" : "team" : used ? "personal" : "free";
  const title = preparing ? "고정 배정 반영 중" : team ? team.teamName ?? "공연조 합주" : used === 6 ? "개인 악기 모두 이용 중" : used ? `개인 악기 ${used}/6 사용` : "빈 시간";
  const typeLabel = team ? `${team.originKind === "FIXED" ? "고정 배정" : "팀 예약"} · 전체 독점` : used ? "악기별 공유" : "개인 · 소속 팀";
  const available = !preparing && phase !== "ENDED" && (block.canReservePersonal || block.canReserveTeam);
  const actionLabel = preparing ? "준비 중 · 상세 보기" : phase === "ENDED" ? "지난 시간 · 상세 보기" : available ? "+ 예약 선택" : reservations.length ? "점유 · 상세 보기" : "상태·한도 확인";
  return { title, typeLabel, kind, phase, used, mine, myTeam, available, actionLabel, team,
    timeStatus: phase === "ENDED" ? "지난 시간" : phase === "IN_PROGRESS" ? `진행 중 · 약 ${remainingMinutes(block, now)}분 남음` : "시작 전" };
}
