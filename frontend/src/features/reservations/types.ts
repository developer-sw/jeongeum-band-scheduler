export type InstrumentCode = "VOCAL" | "KEYBOARD" | "ELECTRIC_GUITAR" | "BASS" | "DRUMS" | "ACOUSTIC_GUITAR";
export type BlockCode = "B01" | "B02" | "B03" | "B04" | "B05" | "B06" | "B07" | "B08" | "B09" | "B10";
export type ReservationPhase = "FUTURE" | "IN_PROGRESS" | "ENDED";
export interface Instrument { code: InstrumentCode; name: string; status: "NORMAL" | "FAULTY"; version: number }
export interface Quota { usedMinutes: number; remainingMinutes: number }
export interface TeamQuota extends Quota { teamId: string; teamName: string }

export interface ReservationSummary {
  id: string;
  version: number;
  subjectType: "PERSONAL" | "TEAM";
  purpose: "PERSONAL" | "REGULAR" | "ADDITIONAL";
  teamId: string | null;
  teamName: string | null;
  instrumentCodes: InstrumentCode[];
  status: "CONFIRMED" | "CANCELED" | "REPLACED";
  phase: ReservationPhase;
  originKind: "MANUAL" | "FIXED" | "CHANGE" | "EXCHANGE";
  quotaExempt: boolean;
  isMine: boolean;
  canCancel: boolean;
}
export interface ReservationDetail extends ReservationSummary {
  ownerUserId: string | null;
  creatorUserId: string;
  date: string;
  blockCode: BlockCode;
  startsAt: string;
  endsAt: string;
  usableFrom: string;
  quotaMinutes: number;
  quotaExempt: boolean;
  originKind: "MANUAL" | "FIXED" | "CHANGE" | "EXCHANGE";
  teamFaultWarnings: InstrumentCode[];
  canceledAt: string | null;
  cancelReason: string | null;
}
export interface ScheduleBlock {
  blockCode: BlockCode;
  startsAt: string;
  endsAt: string;
  durationMinutes: 60 | 90;
  phase: ReservationPhase;
  remainingMinutes: number;
  canReservePersonal: boolean;
  canReserveTeam: boolean;
  availableInstrumentCodes: InstrumentCode[];
  faultyInstrumentCodes: InstrumentCode[];
  teamWarnings: InstrumentCode[];
  reservations: ReservationSummary[];
}
export interface ScheduleDay { date: string; openingStatus: "PREPARING" | "OPEN"; personalQuota: Quota; teamQuotas: TeamQuota[]; blocks: ScheduleBlock[] }
export interface Schedule { today: string; bookableThrough: string; serverTime: string; timeZone: "Asia/Seoul"; instruments: Instrument[]; days: ScheduleDay[] }
export interface ScheduleSnapshot extends Schedule { receivedAtMillis: number }
export type CreateReservationInput = { subjectType: "PERSONAL"; date: string; blockCode: BlockCode; instrumentCodes: [InstrumentCode] } | { subjectType: "TEAM"; date: string; blockCode: BlockCode; teamId: string };
export interface CancelReservationInput { expectedVersion: number; reason?: string }
export type ReservationIntent = { kind: "CREATE"; input: CreateReservationInput } | { kind: "CANCEL"; reservationId: string; input: CancelReservationInput };
