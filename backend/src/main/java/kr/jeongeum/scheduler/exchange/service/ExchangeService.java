package kr.jeongeum.scheduler.exchange.service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.exchange.api.*;
import kr.jeongeum.scheduler.exchange.domain.ExchangeRequest;
import kr.jeongeum.scheduler.exchange.repository.ExchangeRepository;
import kr.jeongeum.scheduler.member.domain.Member;
import kr.jeongeum.scheduler.member.repository.MemberRepository;
import kr.jeongeum.scheduler.reservation.api.ReservationResponse;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import kr.jeongeum.scheduler.reservation.domain.Reservation;
import kr.jeongeum.scheduler.reservation.domain.TimeBlock;
import kr.jeongeum.scheduler.reservation.repository.ReservationRepository;
import kr.jeongeum.scheduler.reservation.service.ReservationViews;
import kr.jeongeum.scheduler.team.repository.TeamRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ExchangeService {
    public record Result(ExchangeResponse response,int status) {}
    private record Outcome(ExchangeResponse response,ApiException error) {}
    private record Groups(List<Reservation> offered,List<Reservation> requested) {
        List<Reservation> all() { List<Reservation> result=new ArrayList<>(offered);result.addAll(requested);return result; }
    }
    private final ExchangeRepository exchanges;
    private final ExchangePolicy policy;
    private final ReservationRepository reservations;
    private final ReservationViews views;
    private final MemberRepository members;
    private final TeamRepository teams;
    private final DirectoryMutationGuard guard;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ExchangeNotificationTransitions notifications;
    private final TransactionTemplate writes;
    public ExchangeService(ExchangeRepository exchanges,ExchangePolicy policy,ReservationRepository reservations,
            ReservationViews views,MemberRepository members,TeamRepository teams,DirectoryMutationGuard guard,
            JdbcTemplate jdbc,Clock clock,ExchangeNotificationTransitions notifications,PlatformTransactionManager transactions) {
        this.exchanges=exchanges;this.policy=policy;this.reservations=reservations;this.views=views;
        this.members=members;this.teams=teams;this.guard=guard;this.jdbc=jdbc;this.clock=clock;
        this.notifications=notifications;
        this.writes=new TransactionTemplate(transactions);this.writes.setTimeout(10);
    }
    public Result create(long actorId,String key,ExchangeRequests.Create input) {
        String digest=ExchangeInputs.key(key);
        List<ExchangeRequests.Item> offered=ExchangeInputs.items(input.offered()),requested=ExchangeInputs.items(input.requested());
        List<Long> allIds=new ArrayList<>();offered.forEach(item -> allIds.add(DirectoryInputs.id(item.reservationId())));
        requested.forEach(item -> allIds.add(DirectoryInputs.id(item.reservationId())));
        if (allIds.stream().distinct().count()!=allIds.size() || (offered.size()==2 && requested.size()==2)) { throw ExchangeInputs.shape(); }
        String fingerprint=ExchangeInputs.digest("POST|/api/v1/exchange-requests|"+fingerprintItems(offered)+"|"+fingerprintItems(requested));
        return writes.execute(status -> {
            guard.lock();ReservationViews.Context context=views.context(actorId);
            ExchangeRepository.Replay replay=exchanges.replay(actorId,digest).orElse(null);
            if (replay!=null) {
                requireFingerprint(replay,fingerprint);
                ExchangeRequest request=exchanges.find(replay.requestId()).orElseThrow(DirectoryInputs::notFound);
                Groups groups=groups(request.id());requireOffered(context,groups.offered());
                request=refresh(request,now());return new Result(response(context,request,groups),replay.status());
            }
            Groups planned=new Groups(load(offered),load(requested));
            requireOffered(context,planned.offered());planned.requested().forEach(this::requireSubject);
            lockGraph(actorId,actorId,planned.all());
            Groups groups=new Groups(policy.chronological(load(offered)),policy.chronological(load(requested)));
            context=views.context(actorId);requireOffered(context,groups.offered());groups.requested().forEach(this::requireSubject);
            for (var item:concat(offered,requested)) {
                Reservation source=reservations.find(DirectoryInputs.id(item.reservationId())).orElseThrow(DirectoryInputs::notFound);
                DirectoryInputs.sameVersion(source.version(),DirectoryInputs.version(item.expectedVersion()));
                if (!"CONFIRMED".equals(source.status())) { throw conflict("VERSION_CONFLICT","예약이 변경되었습니다. 다시 선택해 주세요."); }
            }
            policy.shape(groups.offered(),groups.requested());Instant now=now();
            Instant expires=groups.all().stream().map(Reservation::startsAt).min(Instant::compareTo).orElseThrow();
            if (!now.isBefore(expires)) { throw conflict("EXCHANGE_EXPIRED","시작한 예약은 교환할 수 없습니다."); }
            requireOpenDays(groups.all(),now);
            ExchangeRequest request=exchanges.insert(actorId,expires,now);
            insertItems(request.id(),"REQUESTER",groups.offered());insertItems(request.id(),"COUNTERPART",groups.requested());
            exchanges.remember(actorId,digest,fingerprint,"POST","/api/v1/exchange-requests",201,request.id(),now);
            notifications.publish(request,"EXCHANGE_REQUESTED",List.of(),now);
            return new Result(response(context,request,groups),201);
        });
    }
    public ExchangeListResponse list(long actorId) {
        return writes.execute(status -> {
            guard.lock();ReservationViews.Context context=views.context(actorId);
            List<ExchangeResponse> result=new ArrayList<>();
            for (long id:exchanges.allIds()) {
                ExchangeRequest request=exchanges.find(id).orElseThrow(DirectoryInputs::notFound);Groups groups=groups(id);
                if (related(context,request,groups)) { result.add(response(context,refresh(request,now()),groups)); }
            }
            return new ExchangeListResponse(now(),List.copyOf(result));
        });
    }
    public ExchangeResponse detail(long actorId,long id) {
        return writes.execute(status -> {
            guard.lock();ReservationViews.Context context=views.context(actorId);
            ExchangeRequest request=exchanges.find(id).orElseThrow(DirectoryInputs::notFound);Groups groups=groups(id);
            requireRelated(context,request,groups);return response(context,refresh(request,now()),groups);
        });
    }
    public ExchangeCandidatesResponse candidates(long actorId,String fromInput,String toInput) {
        return writes.execute(status -> {
            ReservationViews.Context context=views.context(actorId);Instant now=now();LocalDate today=LocalDate.ofInstant(now,TimeBlock.SEOUL);
            LocalDate from=fromInput==null?today:date(fromInput),to=toInput==null?today.plusDays(14):date(toInput);
            if (from.isBefore(today) || to.isAfter(today.plusDays(14)) || to.isBefore(from)) { throw ApiException.invalidInput(); }
            List<ExchangeCandidate> result=new ArrayList<>();
            for (Reservation source:reservations.between(from,to)) {
                if (!"CONFIRMED".equals(source.status()) || !now.isBefore(source.startsAt()) || !reservations.prepared(source.date()) || !subjectActive(source)) { continue; }
                var summary=views.summary(context,source,reservations.instrumentCodes(source.id()),now);
                String bundleKey=ExchangeInputs.digest(source.ownerType()+"|"+source.ownerUserId()+"|"+source.teamId()+"|"+source.creatorUserId());
                result.add(new ExchangeCandidate(summary.id(),summary.version(),summary.subjectType(),summary.purpose(),summary.teamId(),summary.teamName(),
                        summary.instrumentCodes(),summary.status(),summary.phase(),summary.isMine(),summary.canCancel(),summary.canChange(),summary.originKind(),
                        summary.quotaExempt(),bundleKey,source.date(),source.blockCode(),source.startsAt(),source.endsAt(),reservations.reservedQuota(source.id()),source.dualAssetException()));
            }
            return new ExchangeCandidatesResponse(now,List.copyOf(result));
        });
    }
    public ExchangeResponse approve(long actorId,long id,String key,ExchangeRequests.Action input) { return action(actorId,id,key,input,"approve"); }
    public ExchangeResponse reject(long actorId,long id,String key,ExchangeRequests.Action input) { return action(actorId,id,key,input,"reject"); }
    public ExchangeResponse cancel(long actorId,long id,String key,ExchangeRequests.Action input) { return action(actorId,id,key,input,"cancel"); }
    private ExchangeResponse action(long actorId,long id,String key,ExchangeRequests.Action input,String action) {
        String digest=ExchangeInputs.key(key);long version=DirectoryInputs.version(input.expectedVersion());
        String route="/api/v1/exchange-requests/"+id+"/"+action;
        String fingerprint=ExchangeInputs.digest("POST|"+route+"|"+version);
        Outcome result=writes.execute(status -> {
            guard.lock();ReservationViews.Context context=views.context(actorId);
            ExchangeRequest request=exchanges.find(id).orElseThrow(DirectoryInputs::notFound);Groups groups=groups(id);
            requireAction(context,request,groups,action);
            ExchangeRepository.Replay replay=exchanges.replay(actorId,digest).orElse(null);
            if (replay!=null) {
                requireFingerprint(replay,fingerprint);if (replay.requestId()!=id) { throw reused(); }
                return new Outcome(response(context,refresh(request,now()),groups),null);
            }
            request=refresh(request,now());ApiException terminal=terminalError(request);
            if (terminal!=null) { return new Outcome(null,terminal); }
            DirectoryInputs.sameVersion(request.version(),version);
            lockGraph(actorId,request.requesterUserId(),groups.all());
            request=exchanges.lock(id).orElseThrow(DirectoryInputs::notFound);groups=groups(id);
            context=views.context(actorId);requireAction(context,request,groups,action);
            request=refresh(request,now());terminal=terminalError(request);
            if (terminal!=null) { return new Outcome(null,terminal); }
            DirectoryInputs.sameVersion(request.version(),version);Instant now=now();
            if ("approve".equals(action)) {
                policy.shape(groups.offered(),groups.requested());
                List<ExchangePolicy.Placement> placements=policy.plan(id,groups.offered(),groups.requested());
                if (placements.stream().anyMatch(ExchangePolicy.Placement::allocationRequired)) { throw conflict("EXCHANGE_ALLOCATION_REQUIRED","두 악기의 블록별 배분 순서를 예약자가 선택해야 합니다."); }
                requireOpenDays(groups.all(),now);validatePlacements(placements,groups.all());
                // Re-read time immediately before writes. Expiry is persisted even when approval returns 409.
                now=now();request=refresh(request,now);terminal=terminalError(request);
                if (terminal!=null) { return new Outcome(null,terminal); }
                List<Long> pendingExchanges=notifications.pendingForReservations(groups.all().stream().map(Reservation::id).toList());
                long operationId=exchanges.operation(id,actorId,now);
                for (Reservation source:groups.all()) {
                    int consumed=reservations.reservedQuota(source.id());reservations.replaceForExchange(source.id(),now,id);
                    if (consumed>0) { reservations.quota(source,operationId,-consumed,"RELEASE",now); }
                }
                Map<String,List<Long>> successors=new HashMap<>();
                List<Reservation> results=new ArrayList<>();
                for (ExchangePolicy.Placement placement:placements) {
                    Reservation successor=reservations.insertExchanged(placement.subject(),placement.date(),placement.block(),placement.dualException(),now,operationId);
                    for (Instrument instrument:reservations.instruments()) {
                        if (placement.codes().contains(instrument.code())) { reservations.claim(successor,instrument,now); }
                    }
                    reservations.quota(successor,operationId,placement.consumption(),"RESERVE",now);
                    for (Reservation source:placement.sources()) { reservations.lineage(operationId,source.id(),successor.id(),now); }
                    successors.computeIfAbsent(placement.side(),ignored -> new ArrayList<>()).add(successor.id());
                    results.add(successor);
                }
                reservations.exchangeOccurrences(groups.offered().stream().map(Reservation::id).toList(),successors.get("REQUESTER"),now);
                reservations.exchangeOccurrences(groups.requested().stream().map(Reservation::id).toList(),successors.get("COUNTERPART"),now);
                exchanges.terminal(id,"COMPLETED",actorId,now,null);
                exchanges.invalidateReservations(groups.all().stream().map(Reservation::id).toList(),now);
                notifications.publish(exchanges.find(id).orElseThrow(),"EXCHANGE_COMPLETED",results,now);
                notifications.invalidated(pendingExchanges,now);
            } else {
                exchanges.terminal(id,"reject".equals(action)?"REJECTED":"CANCELED",actorId,now,null);
                notifications.publish(exchanges.find(id).orElseThrow(),"reject".equals(action)?"EXCHANGE_REJECTED":"EXCHANGE_WITHDRAWN",List.of(),now);
            }
            exchanges.remember(actorId,digest,fingerprint,"POST",route,200,id,now);
            return new Outcome(response(context,exchanges.find(id).orElseThrow(DirectoryInputs::notFound),groups(id)),null);
        });
        if (result.error()!=null) { throw result.error(); }
        return result.response();
    }
    public ExchangeResponse allocate(long actorId,long id,String key,ExchangeRequests.Allocation input) {
        String digest=ExchangeInputs.key(key);long version=DirectoryInputs.version(input.expectedVersion());long sourceId=DirectoryInputs.id(input.reservationId());
        List<String> codes=input.instrumentCodesOrder();
        if (codes==null || codes.size()!=2 || codes.stream().anyMatch(Objects::isNull) || codes.getFirst().equals(codes.getLast())) { throw ApiException.invalidInput(); }
        String route="/api/v1/exchange-requests/"+id+"/instrument-allocation";
        String fingerprint=ExchangeInputs.digest("PATCH|"+route+"|"+version+"|"+sourceId+"|"+String.join(",",codes));
        Outcome result=writes.execute(status -> {
            guard.lock();ReservationViews.Context context=views.context(actorId);
            ExchangeRequest request=exchanges.find(id).orElseThrow(DirectoryInputs::notFound);Groups groups=groups(id);
            Reservation source=groups.all().stream().filter(value -> value.id()==sourceId).findFirst().orElseThrow(DirectoryInputs::notFound);
            requireAllocationOwner(actorId,source);
            ExchangeRepository.Replay replay=exchanges.replay(actorId,digest).orElse(null);
            if (replay!=null) { requireFingerprint(replay,fingerprint);return new Outcome(response(context,refresh(request,now()),groups),null); }
            request=refresh(request,now());ApiException terminal=terminalError(request);
            if (terminal!=null) { return new Outcome(null,terminal); }
            DirectoryInputs.sameVersion(request.version(),version);
            lockGraph(actorId,request.requesterUserId(),groups.all());request=exchanges.lock(id).orElseThrow(DirectoryInputs::notFound);groups=groups(id);
            request=refresh(request,now());terminal=terminalError(request);if (terminal!=null) { return new Outcome(null,terminal); }
            DirectoryInputs.sameVersion(request.version(),version);
            if (!policy.allocationIds(groups.offered(),groups.requested()).contains(sourceId)
                    || !new TreeSet<>(reservations.instrumentCodes(sourceId)).equals(new TreeSet<>(codes))) { throw ApiException.invalidInput(); }
            List<Long> assetIds=new ArrayList<>();
            for (String code:codes) { assetIds.add(reservations.instruments().stream().filter(value -> value.code().equals(code)).findFirst().orElseThrow(ApiException::invalidInput).id()); }
            exchanges.allocate(id,sourceId,assetIds);exchanges.remember(actorId,digest,fingerprint,"PATCH",route,200,id,now());
            return new Outcome(response(views.context(actorId),exchanges.find(id).orElseThrow(DirectoryInputs::notFound),groups),null);
        });
        if (result.error()!=null) { throw result.error(); }return result.response();
    }
    /** Called by the worker; uses the same guard as every reservation and directory write. */
    public void refreshPending() { writes.executeWithoutResult(status -> { guard.lock();refreshPendingUnderGuard(); }); }
    /** Directory mutations already hold the guard and invoke this inside their transaction. */
    public void refreshPendingUnderGuard() {
        Instant now=now();for (long id:exchanges.pendingIds()) { refresh(exchanges.lock(id).orElseThrow(DirectoryInputs::notFound),now); }
    }
    private ExchangeRequest refresh(ExchangeRequest request,Instant now) {
        if (!request.pending()) { return request; }
        Groups groups=groups(request.id());String reason=null;
        for (ExchangeRepository.Item item:exchanges.items(request.id())) {
            Reservation source=reservations.find(item.reservationId()).orElseThrow(DirectoryInputs::notFound);
            if (!"CONFIRMED".equals(source.status()) || source.version()!=item.expectedVersion()) { reason="SOURCE_CHANGED";break; }
            if (!subjectActive(source)) { reason="SUBJECT_INACTIVE";break; }
        }
        if (reason==null) {
            try {
                ReservationViews.Context requester=views.context(request.requesterUserId());
                if (groups.offered().stream().anyMatch(source -> !views.cancelAuthority(requester,source))) { reason="REQUESTER_PERMISSION_LOST"; }
            } catch (ApiException error) {
                if (error.status()!=403 && error.status()!=404) { throw error; }
                reason="REQUESTER_PERMISSION_LOST";
            }
        }
        if (reason!=null) {
            exchanges.terminal(request.id(),"INVALIDATED",null,now,reason);
            notifications.publish(exchanges.find(request.id()).orElseThrow(),"EXCHANGE_INVALIDATED",List.of(),now);
        } else if (!now.isBefore(request.expiresAt())) {
            exchanges.terminal(request.id(),"EXPIRED",null,now,"EARLIEST_START_REACHED");
            notifications.publish(exchanges.find(request.id()).orElseThrow(),"EXCHANGE_EXPIRED",List.of(),now);
        }
        return exchanges.find(request.id()).orElseThrow(DirectoryInputs::notFound);
    }
    private void validatePlacements(List<ExchangePolicy.Placement> placements,List<Reservation> sources) {
        Map<ReservationRepository.QuotaKey,Integer> changes=new HashMap<>();
        List<Long> excluded=sources.stream().map(Reservation::id).toList();
        String placeholders=String.join(",",java.util.Collections.nCopies(excluded.size(),"?"));
        HashSet<String> occupiedAssets=new HashSet<>(),personalTimes=new HashSet<>();
        for (Reservation source:sources) {
            changes.merge(new ReservationRepository.QuotaKey(source.date(),source.ownerUserId(),source.teamId()),-reservations.reservedQuota(source.id()),Integer::sum);
        }
        List<Instrument> instruments=reservations.instruments();
        for (ExchangePolicy.Placement placement:placements) {
            Reservation subject=placement.subject();
            changes.merge(new ReservationRepository.QuotaKey(placement.date(),subject.ownerUserId(),subject.teamId()),placement.consumption(),Integer::sum);
            List<Object> args=new ArrayList<>(List.of(placement.date(),placement.block().name()));args.addAll(excluded);
            List<String> occupied=jdbc.queryForList("SELECT a.code FROM reservation_assets c JOIN assets a ON a.id=c.asset_id WHERE c.reservation_date=? AND c.time_block_code=? AND c.released_at IS NULL AND c.reservation_id NOT IN ("+placeholders+")",String.class,args.toArray());
            for (String code:placement.codes()) {
                if (occupied.contains(code) || !occupiedAssets.add(placement.date()+"|"+placement.block().name()+"|"+code)) { throw conflict("RESERVATION_CONFLICT","교환 후 시간이나 악기에 다른 예약이 있습니다."); }
            }
            if (subject.ownerUserId()!=null) {
                if (instruments.stream().anyMatch(asset -> placement.codes().contains(asset.code()) && asset.faulty())) { throw conflict("ASSET_FAULTY","고장 안내가 있는 악기로 개인 예약을 옮길 수 없습니다."); }
                String personalKey=subject.ownerUserId()+"|"+placement.date()+"|"+placement.block().name();
                args=new ArrayList<>(List.of(subject.ownerUserId(),placement.date(),placement.block().name()));args.addAll(excluded);
                boolean overlap=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM reservations WHERE owner_user_id=? AND reservation_date=? AND time_block_code=? AND status='CONFIRMED' AND id NOT IN ("+placeholders+"))",Boolean.class,args.toArray()));
                if (overlap || !personalTimes.add(personalKey)) { throw conflict("PERSONAL_OVERLAP","같은 시간에 개인 예약을 두 건 가질 수 없습니다."); }
            }
        }
        for (var entry:changes.entrySet()) {
            var quota=entry.getKey();int usage=reservations.usedMinutes(quota.date(),quota.userId(),quota.teamId())+entry.getValue();
            if (usage<0 || usage>120) { throw conflict("DAILY_LIMIT_EXCEEDED","교환 후 60분 예약은 주체별 하루 두 블록까지 가능합니다."); }
        }
    }
    private ExchangeResponse response(ReservationViews.Context context,ExchangeRequest request,Groups groups) {
        Instant now=now();List<ExchangePolicy.Placement> placements=policy.plan(request.id(),groups.offered(),groups.requested());
        List<Long> allocationIds=policy.allocationIds(groups.offered(),groups.requested());
        List<String> allocatable=groups.all().stream().filter(source -> allocationIds.contains(source.id()) && Objects.equals(source.ownerUserId(),context.actor().id()) && request.pending())
                .map(source -> Long.toString(source.id())).toList();
        boolean counterpart=counterpartAuthority(context,groups.requested());
        List<ExchangePlacement> preview=placements.stream().map(placement -> {
            Reservation source=placement.subject();String teamName=source.teamId()==null?null:teams.findById(source.teamId()).map(team -> team.name()).orElse(null);
            return new ExchangePlacement(placement.side(),placement.sources().stream().map(value -> Long.toString(value.id())).toList(),source.ownerType(),source.purpose(),
                    string(source.teamId()),teamName,string(source.ownerUserId()),Long.toString(source.creatorUserId()),placement.date(),placement.block().name(),placement.codes(),
                    placement.allocationRequired(),placement.consumption(),source.teamId()==null?List.of():context.faults());
        }).toList();
        List<ReservationResponse> successors="COMPLETED".equals(request.status())?exchanges.successors(request.id()).stream()
                .map(id -> views.detail(context,reservations.find(id).orElseThrow(DirectoryInputs::notFound),now)).toList():List.of();
        return new ExchangeResponse(Long.toString(request.id()),request.version(),request.status(),Long.toString(request.requesterUserId()),request.expiresAt(),request.createdAt(),request.processedAt(),
                string(request.processedByUserId()),request.stateReason(),now,details(context,groups.offered(),now),details(context,groups.requested(),now),preview,
                allocationIds.stream().map(Object::toString).toList(),allocatable,request.pending() && counterpart && placements.stream().noneMatch(ExchangePolicy.Placement::allocationRequired),
                request.pending() && counterpart,request.pending() && (context.actor().id()==request.requesterUserId() || context.actor().globalAdmin()),
                "COMPLETED".equals(request.status())?groups.all().stream().map(source -> Long.toString(source.id())).toList():List.of(),successors);
    }
    private Groups groups(long id) {
        List<Reservation> offered=new ArrayList<>(),requested=new ArrayList<>();
        for (ExchangeRepository.Item item:exchanges.items(id)) {
            ("REQUESTER".equals(item.side())?offered:requested).add(reservations.find(item.reservationId()).orElseThrow(DirectoryInputs::notFound));
        }
        return new Groups(policy.chronological(offered),policy.chronological(requested));
    }
    private void lockGraph(long actorId,long requesterId,List<Reservation> sources) {
        TreeSet<Long> users=new TreeSet<>(List.of(actorId,requesterId)),teamIds=new TreeSet<>();TreeSet<LocalDate> dates=new TreeSet<>();
        for (Reservation source:sources) { users.add(source.creatorUserId());if (source.ownerUserId()!=null) { users.add(source.ownerUserId()); }
            if (source.teamId()!=null) { teamIds.add(source.teamId()); }dates.add(source.date()); }
        for (long id:users) { members.lockById(id).orElseThrow(DirectoryInputs::notFound); }
        for (long id:teamIds) { teams.lockById(id).orElseThrow(DirectoryInputs::notFound); }
        reservations.lockInstruments();for (LocalDate date:dates) { reservations.ensureAndLockDay(date,now()); }
        for (long id:sources.stream().map(Reservation::id).sorted().toList()) { reservations.lock(id).orElseThrow(DirectoryInputs::notFound); }
    }
    private void requireOpenDays(List<Reservation> sources,Instant now) {
        LocalDate today=LocalDate.ofInstant(now,TimeBlock.SEOUL);
        for (Reservation source:sources) { if (source.date().isBefore(today) || source.date().isAfter(today.plusDays(14)) || !reservations.prepared(source.date())) {
            throw conflict("DATE_NOT_OPEN","교환 대상 날짜가 아직 개방되지 않았습니다.");
        } }
    }
    private void requireOffered(ReservationViews.Context context,List<Reservation> offered) {
        for (Reservation source:offered) { views.requireChange(context,source);requireSubject(source); }
    }
    private void requireSubject(Reservation source) {
        if (!subjectActive(source)) { throw conflict("EXCHANGE_INVALIDATED","현재 활성 회원이나 팀의 예약만 교환할 수 있습니다."); }
    }
    private boolean subjectActive(Reservation source) {
        return source.ownerUserId()!=null?members.findById(source.ownerUserId()).map(Member::active).orElse(false)
                :teams.findById(source.teamId()).map(team -> team.active()).orElse(false);
    }
    private boolean counterpartAuthority(ReservationViews.Context context,List<Reservation> requested) {
        return !requested.isEmpty() && requested.stream().allMatch(source -> views.cancelAuthority(context,source));
    }
    private boolean related(ReservationViews.Context context,ExchangeRequest request,Groups groups) {
        return context.actor().globalAdmin() || context.actor().id()==request.requesterUserId() || groups.all().stream().anyMatch(source -> views.mine(context,source));
    }
    private void requireRelated(ReservationViews.Context context,ExchangeRequest request,Groups groups) {
        if (!related(context,request,groups)) { throw DirectoryInputs.notFound(); }
    }
    private void requireAction(ReservationViews.Context context,ExchangeRequest request,Groups groups,String action) {
        if ("cancel".equals(action)) {
            if (context.actor().id()!=request.requesterUserId() && !context.actor().globalAdmin()) { throw MemberAccess.forbidden(); }
        } else if (!counterpartAuthority(context,groups.requested())) { throw MemberAccess.forbidden(); }
    }
    private static void requireAllocationOwner(long actorId,Reservation source) {
        if (!Objects.equals(source.ownerUserId(),actorId)) { throw MemberAccess.forbidden(); }
    }
    private static List<ExchangeRequests.Item> concat(List<ExchangeRequests.Item> a,List<ExchangeRequests.Item> b) { List<ExchangeRequests.Item> values=new ArrayList<>(a);values.addAll(b);return values; }
    private List<Reservation> load(List<ExchangeRequests.Item> values) { return values.stream().map(item -> reservations.find(DirectoryInputs.id(item.reservationId())).orElseThrow(DirectoryInputs::notFound)).toList(); }
    private void insertItems(long id,String side,List<Reservation> values) { for (int index=0;index<values.size();index++) { var source=values.get(index);exchanges.item(id,source.id(),source.version(),side,index); } }
    private List<ReservationResponse> details(ReservationViews.Context context,List<Reservation> sources,Instant now) { return sources.stream().map(source -> views.detail(context,source,now)).toList(); }
    private String fingerprintItems(List<ExchangeRequests.Item> values) { return values.stream().sorted(java.util.Comparator.comparing(item -> Long.parseLong(item.reservationId())))
            .map(item -> item.reservationId()+":"+item.expectedVersion()).collect(java.util.stream.Collectors.joining(",")); }
    private static LocalDate date(String value) { try { if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) { throw ApiException.invalidInput(); }return LocalDate.parse(value); }catch(java.time.format.DateTimeParseException error) { throw ApiException.invalidInput(); } }
    private static ApiException terminalError(ExchangeRequest request) {
        return switch(request.status()) { case "PENDING" -> null;case "EXPIRED" -> conflict("EXCHANGE_EXPIRED","교환 대상 예약의 시작 시각이 지나 요청이 만료되었습니다.");
            case "INVALIDATED" -> conflict("EXCHANGE_INVALIDATED","예약 또는 요청자의 권한이 바뀌어 교환 요청이 무효화되었습니다.");
            default -> conflict("EXCHANGE_NOT_PENDING","이미 처리된 교환 요청입니다."); };
    }
    private static ApiException conflict(String code,String message) { return new ApiException(409,code,message); }
    private static ApiException reused() { return conflict("IDEMPOTENCY_KEY_REUSED","같은 재시도 키를 다른 요청에 사용할 수 없습니다."); }
    private static void requireFingerprint(ExchangeRepository.Replay replay,String fingerprint) { if (!replay.fingerprint().equals(fingerprint)) { throw reused(); } }
    private static String string(Long value) { return value==null?null:Long.toString(value); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
