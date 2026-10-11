"use client";

import { useEffect, useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import { refreshReservations, scheduleQuery } from "./queries";
import { blockLabel, dateLabel, seoulDate } from "./time";
import type { CreateReservationInput, ReservationDetail } from "./types";
import { useReservationAction } from "./use-reservation-action";
import { useServerClock } from "./use-server-clock";

export function useBookingWorkspace(scope: string) {
  const client = useQueryClient();
  const query = useQuery(scheduleQuery(scope));
  const schedule = query.data;
  const now = useServerClock(schedule);
  const today = schedule && now ? seoulDate(now) : schedule?.today;
  const action = useReservationAction();
  const [notice, setNotice] = useState<string | null>(null);
  const processingRef = useRef(false);
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    if (today && schedule && today !== schedule.today) {
      void client.invalidateQueries({ queryKey: ["reservations", scope, "schedule"] });
    }
  }, [client, today, schedule, scope]);

  async function completed(result?: ReservationDetail) {
    if (result) {
      setNotice(result.status === "CANCELED"
        ? `예약 #${result.id}의 취소 상태를 확인했어요.`
        : `예약 #${result.id}이 확정됐어요. ${dateLabel(result.date)} ${blockLabel(result)}`);
    }
    await refreshReservations(client);
    return result;
  }

  // Keep the form locked until both the write and its cache refresh finish.
  // The ref also rejects another call before React has rendered the busy state.
  async function perform(request: () => Promise<ReservationDetail | undefined>, clearNotice: boolean): Promise<ReservationDetail | undefined> {
    if (processingRef.current) return undefined;
    processingRef.current = true;
    setProcessing(true);
    if (clearNotice) setNotice(null);
    try {
      return await completed(await request());
    } finally {
      processingRef.current = false;
      setProcessing(false);
    }
  }

  async function create(input: CreateReservationInput): Promise<ReservationDetail | undefined> {
    return perform(() => action.run({ kind: "CREATE", input }), true);
  }

  async function cancel(reservation: ReservationDetail): Promise<ReservationDetail | undefined> {
    return perform(() => action.run({
      kind: "CANCEL", reservationId: reservation.id,
      input: { expectedVersion: reservation.version },
    }), true);
  }

  async function retry(): Promise<ReservationDetail | undefined> {
    return perform(() => action.retry(), false);
  }

  return {
    query, schedule, now, today, action,
    busy: processing || action.pending || action.uncertain,
    notice, setNotice, create, cancel, retry,
    refresh: () => refreshReservations(client),
  };
}
