package kr.jeongeum.scheduler.notification.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kr.jeongeum.scheduler.notice.api.NoticeResponse;
import kr.jeongeum.scheduler.notification.repository.NotificationRepository;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Inbox rows belong to the initiating business transaction; external Push is a later, separate delivery stage. */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class NotificationPublisher {
    private static final DateTimeFormatter WHEN=DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.of("Asia/Seoul"));
    private static final Set<String> EXCHANGE_EVENTS=Set.of("EXCHANGE_REQUESTED","EXCHANGE_COMPLETED","EXCHANGE_REJECTED","EXCHANGE_WITHDRAWN","EXCHANGE_EXPIRED","EXCHANGE_INVALIDATED");
    private final NotificationRepository notifications;
    private final ReminderPlans reminders;
    public NotificationPublisher(NotificationRepository notifications,ReminderPlans reminders) { this.notifications=notifications; this.reminders=reminders; }
    public void importantNotice(NoticeResponse notice,Instant now) {
        long id=Long.parseLong(notice.id());
        for (long user : notifications.activeUsers()) {
            notifications.insert(user,"notice:"+id+":IMPORTANT","IMPORTANT_NOTICE",null,null,null,id,
                    "중요 공지가 등록됐어요",notice.title(),"/notices/"+id,now);
        }
    }
    public void reservationCreated(Reservation reservation,Long operationId,Instant now) {
        reminders.schedule(reservation,now);
        reservationEvent(reservation,"reservation:"+reservation.id()+":CREATED","RESERVATION_CREATED",operationId,
                "합주실 예약이 등록됐어요",describe(reservation),now);
    }
    public void reservationChanged(Reservation old,Reservation next,Long operationId,Instant now) {
        requireOperation(operationId);
        reminders.cancel(old.id(),now);
        reminders.schedule(next,now);
        reservationEvent(next,"operation:"+operationId+":reservation:"+old.id()+":CHANGED","RESERVATION_CHANGED",operationId,
                "합주실 예약이 변경됐어요",describe(old)+" → "+describe(next),now);
    }
    public void reservationCancelled(Reservation reservation,Long operationId,Instant now) {
        requireOperation(operationId);
        reminders.cancel(reservation.id(),now);
        reservationEvent(reservation,"operation:"+operationId+":reservation:"+reservation.id()+":CANCELED","RESERVATION_CANCELED",operationId,
                "합주실 예약이 취소됐어요",describe(reservation),now);
    }
    public void exchangeEvent(long requestId,String eventType,List<Reservation> sources,List<Reservation> results,long requestedByUserId,Instant now) {
        if (!EXCHANGE_EVENTS.contains(eventType)) { throw new IllegalArgumentException("Unsupported exchange inbox event"); }
        if ("EXCHANGE_COMPLETED".equals(eventType)) {
            for(Reservation source:sources) { reminders.cancel(source.id(),now); }
            for(Reservation result:results) { reminders.schedule(result,now); }
        }
        Set<Long> users=new LinkedHashSet<>();
        for (Reservation source : sources) { users.addAll(recipients(source)); }
        users.addAll(notifications.activePersonal(requestedByUserId));
        String title=switch (eventType) {
            case "EXCHANGE_REQUESTED" -> "예약 교환 요청이 도착했어요";
            case "EXCHANGE_COMPLETED" -> "예약 교환이 완료됐어요";
            case "EXCHANGE_REJECTED" -> "예약 교환 요청이 거절됐어요";
            case "EXCHANGE_WITHDRAWN" -> "예약 교환 요청이 철회됐어요";
            case "EXCHANGE_EXPIRED" -> "예약 교환 요청이 만료됐어요";
            default -> "예약 교환 요청이 무효화됐어요";
        };
        List<Reservation> display=results.isEmpty()?sources:results;
        String body=display.stream().map(this::describe).distinct().reduce((a,b) -> a+" / "+b).orElse("현재 교환 상태를 확인해 주세요.");
        if ("EXCHANGE_INVALIDATED".equals(eventType)) {
            String reason=notifications.exchangeReason(requestId);
            body+=" · "+switch (reason==null?"":reason) {
                case "SOURCE_CHANGED" -> "관련 예약이 변경되거나 취소됐어요.";
                case "SUBJECT_INACTIVE" -> "예약 주체의 이용 상태가 변경됐어요.";
                case "REQUESTER_PERMISSION_LOST" -> "요청자의 예약 권한이 변경됐어요.";
                default -> "현재 상태에서 교환을 진행할 수 없어요.";
            };
        }
        for (long user : users) {
            notifications.insert(user,"exchange:"+requestId+":"+eventType,eventType,null,null,requestId,null,
                    title,body,"/exchanges?exchangeId="+requestId,now);
        }
    }
    private void reservationEvent(Reservation reservation,String key,String type,Long operation,String title,String body,Instant now) {
        for (long user : recipients(reservation)) {
            notifications.insert(user,key,type,operation,reservation.id(),null,null,title,body,
                    "/reservations?reservationId="+reservation.id(),now);
        }
    }
    private List<Long> recipients(Reservation reservation) {
        return "PERSONAL".equals(reservation.ownerType())?notifications.activePersonal(reservation.ownerUserId()):notifications.activeTeam(reservation.teamId());
    }
    private String describe(Reservation reservation) {
        String resource="TEAM".equals(reservation.ownerType())?"팀 합주":notifications.instrumentNames(reservation.id());
        return WHEN.format(reservation.startsAt())+"~"+DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Asia/Seoul")).format(reservation.endsAt())+" · "+resource;
    }
    private static void requireOperation(Long operation) {
        if (operation==null || operation<=0) { throw new IllegalArgumentException("Reservation mutation requires an operation"); }
    }
}
