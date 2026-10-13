package kr.jeongeum.scheduler.exchange.service;

import java.time.Instant;
import java.util.List;
import kr.jeongeum.scheduler.exchange.domain.ExchangeRequest;
import kr.jeongeum.scheduler.exchange.repository.ExchangeRepository;
import kr.jeongeum.scheduler.notification.service.NotificationPublisher;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists business-event notifications in the initiating transaction, never in an after-commit callback. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ExchangeNotificationTransitions {
    private final ExchangeRepository exchanges;
    private final ReservationRepository reservations;
    private final NotificationPublisher notifications;

    public ExchangeNotificationTransitions(ExchangeRepository exchanges, ReservationRepository reservations,
            NotificationPublisher notifications) {
        this.exchanges = exchanges; this.reservations = reservations; this.notifications = notifications;
    }

    public List<Long> pendingForReservations(List<Long> reservationIds) {
        return exchanges.pendingForReservations(reservationIds);
    }

    /** Only IDs captured while PENDING may be passed; unchanged and completing requests are ignored. */
    public void invalidated(List<Long> previouslyPendingIds, Instant now) {
        for (long id : previouslyPendingIds) {
            ExchangeRequest request = exchanges.find(id).orElseThrow();
            if ("INVALIDATED".equals(request.status())) { publish(request, "EXCHANGE_INVALIDATED", List.of(), now); }
        }
    }

    public void publish(ExchangeRequest request, String eventType, List<Reservation> results, Instant now) {
        List<Reservation> sources = exchanges.items(request.id()).stream()
                .map(item -> reservations.find(item.reservationId()).orElseThrow()).toList();
        notifications.exchangeEvent(request.id(), eventType, sources, results, request.requesterUserId(), now);
    }
}
