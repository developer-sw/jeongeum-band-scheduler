package kr.jeongeum.scheduler.exchange.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import kr.jeongeum.scheduler.exchange.repository.ExchangeRepository;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import org.springframework.stereotype.Service;

/** Convert each owner's source group into the other group's chronological blocks, never ownership. */
@Service
public class ExchangePolicy {
    public record Placement(String side,List<Reservation> sources,LocalDate date,TimeBlock block,List<String> codes,
            boolean allocationRequired,boolean dualException) {
        public Reservation subject() { return sources.getFirst(); }
        public int consumption() { return block.durationMinutes()==60 ? 60 : 0; }
    }
    private final ReservationRepository reservations;
    private final ExchangeRepository exchanges;
    public ExchangePolicy(ReservationRepository reservations,ExchangeRepository exchanges) {
        this.reservations=reservations;this.exchanges=exchanges;
    }
    public List<Reservation> chronological(List<Reservation> values) {
        return values.stream().sorted(Comparator.comparing(Reservation::startsAt).thenComparingLong(Reservation::id)).toList();
    }
    public void shape(List<Reservation> offered,List<Reservation> requested) {
        if (offered.isEmpty() || requested.isEmpty() || offered.size()>2 || requested.size()>2
                || (offered.size()==2 && requested.size()==2)) { throw ExchangeInputs.shape(); }
        group(offered); group(requested);
        if (offered.size()==2 && TimeBlock.require(requested.getFirst().blockCode()).durationMinutes()!=90) { throw ExchangeInputs.shape(); }
        if (requested.size()==2 && TimeBlock.require(offered.getFirst().blockCode()).durationMinutes()!=90) { throw ExchangeInputs.shape(); }
        if (offered.size()==1 && requested.size()==1) {
            for (int index=0;index<2;index++) {
                Reservation source=(index==0?offered:requested).getFirst();
                Reservation target=(index==0?requested:offered).getFirst();
                if (source.ownerUserId()!=null && reservations.instrumentCodes(source.id()).size()==2
                        && TimeBlock.require(target.blockCode()).durationMinutes()==60) { throw ExchangeInputs.shape(); }
            }
        }
    }
    private void group(List<Reservation> values) {
        for (Reservation value:values) {
            List<String> codes=reservations.instrumentCodes(value.id());
            if (value.ownerUserId()!=null && (codes.isEmpty() || codes.size()>2
                    || (codes.size()==2 && (!value.dualAssetException() || TimeBlock.require(value.blockCode()).durationMinutes()!=90)))) { throw ExchangeInputs.shape(); }
        }
        if (values.size()==2) {
            List<Reservation> ordered=chronological(values);
            Reservation first=ordered.getFirst(),second=ordered.getLast();
            if (!first.date().equals(second.date()) || !first.endsAt().equals(second.startsAt())
                    || TimeBlock.require(first.blockCode()).durationMinutes()!=60 || TimeBlock.require(second.blockCode()).durationMinutes()!=60
                    || !Objects.equals(first.ownerUserId(),second.ownerUserId()) || !Objects.equals(first.teamId(),second.teamId())
                    || first.creatorUserId()!=second.creatorUserId() || !first.purpose().equals(second.purpose())) { throw ExchangeInputs.shape(); }
        }
    }
    public List<Long> allocationIds(List<Reservation> offered,List<Reservation> requested) {
        List<Long> result=new ArrayList<>();
        if (offered.size()==1 && requested.size()==2 && offered.getFirst().ownerUserId()!=null
                && reservations.instrumentCodes(offered.getFirst().id()).size()==2) { result.add(offered.getFirst().id()); }
        if (requested.size()==1 && offered.size()==2 && requested.getFirst().ownerUserId()!=null
                && reservations.instrumentCodes(requested.getFirst().id()).size()==2) { result.add(requested.getFirst().id()); }
        return List.copyOf(result);
    }
    public List<Placement> plan(long requestId,List<Reservation> offered,List<Reservation> requested) {
        List<Placement> result=new ArrayList<>();
        result.addAll(side(requestId,"REQUESTER",offered,requested));
        result.addAll(side(requestId,"COUNTERPART",requested,offered));
        return List.copyOf(result);
    }
    private List<Placement> side(long requestId,String side,List<Reservation> sources,List<Reservation> destinations) {
        Reservation subject=sources.getFirst();
        TreeSet<String> union=new TreeSet<>();
        for (Reservation source:sources) { union.addAll(reservations.instrumentCodes(source.id())); }
        List<Reservation> targets=chronological(destinations);
        boolean needsOrder=subject.ownerUserId()!=null && sources.size()==1 && targets.size()==2 && union.size()==2;
        List<String> order=needsOrder?exchanges.allocation(requestId,subject.id()):List.of();
        List<Placement> result=new ArrayList<>();
        for (int index=0;index<targets.size();index++) {
            Reservation target=targets.get(index); TimeBlock block=TimeBlock.require(target.blockCode());
            boolean missing=needsOrder && order.size()!=2;
            List<String> codes=needsOrder?(missing?List.of():List.of(order.get(index))):List.copyOf(union);
            boolean dual=subject.ownerUserId()!=null && block.durationMinutes()==90
                    && (union.size()==2 || sources.stream().anyMatch(Reservation::dualAssetException));
            result.add(new Placement(side,List.copyOf(sources),target.date(),block,codes,missing,dual));
        }
        return result;
    }
}
