package kr.jeongeum.scheduler.instrument.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import kr.jeongeum.scheduler.common.api.ApiException;
import kr.jeongeum.scheduler.common.api.DirectoryInputs;
import kr.jeongeum.scheduler.common.concurrency.DirectoryMutationGuard;
import kr.jeongeum.scheduler.common.security.MemberAccess;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultListResponse;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultRequests;
import kr.jeongeum.scheduler.instrument.api.InstrumentFaultResponse;
import kr.jeongeum.scheduler.instrument.domain.InstrumentFault;
import kr.jeongeum.scheduler.instrument.repository.InstrumentFaultRepository;
import kr.jeongeum.scheduler.reservation.domain.Instrument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class InstrumentFaultService {
    public record Result(InstrumentFaultResponse response, int status) {}
    private final InstrumentFaultRepository faults;
    private final MemberAccess access;
    private final DirectoryMutationGuard guard;
    private final Clock clock;
    private final TransactionTemplate writes;
    private final TransactionTemplate reads;
    public InstrumentFaultService(InstrumentFaultRepository faults, MemberAccess access, DirectoryMutationGuard guard,
            Clock clock, PlatformTransactionManager transactions) {
        this.faults=faults; this.access=access; this.guard=guard; this.clock=clock;
        this.writes=new TransactionTemplate(transactions); this.writes.setTimeout(10);
        this.reads=new TransactionTemplate(transactions); this.reads.setReadOnly(true); this.reads.setTimeout(10);
    }
    public InstrumentFaultListResponse list(long actorId, String instrumentCode, String beforeInput, Integer limitInput) {
        String code=instrumentCode == null ? null : InstrumentFaultInputs.code(instrumentCode);
        Long before=DirectoryInputs.optionalId(beforeInput);
        int limit=limitInput == null ? 20 : limitInput;
        if (limit < 1 || limit > 50) { throw ApiException.invalidInput(); }
        return reads.execute(status -> {
            access.administrator(actorId);
            List<InstrumentFaultResponse> rows=faults.history(code,before,limit+1);
            boolean more=rows.size()>limit;
            List<InstrumentFaultResponse> page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
            String next=more ? page.getLast().id() : null;
            return new InstrumentFaultListResponse(now(),page,next);
        });
    }
    public Result create(long actorId, String key, InstrumentFaultRequests.Create input) {
        if (input == null) { throw ApiException.invalidInput(); }
        String digest=InstrumentFaultInputs.key(key), code=InstrumentFaultInputs.code(input.instrumentCode());
        long version=DirectoryInputs.version(input.expectedAssetVersion());
        String description=InstrumentFaultInputs.description(input.description());
        String route="/api/v1/instrument-faults";
        String fingerprint=InstrumentFaultInputs.digest("POST|"+route+"|"+code+"|"+version+"|"+description);
        return writes.execute(status -> {
            guard.lock(); access.administratorForMutation(actorId);
            InstrumentFaultRepository.Replay replay=faults.replay(actorId,digest).orElse(null);
            if (replay != null) {
                requireFingerprint(replay,fingerprint);
                return new Result(faults.response(replay.incidentId()),replay.status());
            }
            Instrument asset=faults.lockAsset(code).orElseThrow(DirectoryInputs::notFound);
            if (asset.faulty()) { throw conflict("INSTRUMENT_ALREADY_FAULTY","이미 고장으로 등록된 악기입니다. 최신 이력을 확인해 주세요."); }
            DirectoryInputs.sameVersion(asset.version(),version);
            Instant now=now();
            faults.assetStatus(asset.id(),"FAULTY");
            InstrumentFault incident=faults.insert(asset.id(),actorId,description,now);
            faults.remember(actorId,digest,fingerprint,route,201,incident.id(),now);
            return new Result(faults.response(incident.id()),201);
        });
    }
    public InstrumentFaultResponse resolve(long actorId, long id, String key, InstrumentFaultRequests.Resolve input) {
        if (id <= 0 || input == null) { throw ApiException.invalidInput(); }
        String digest=InstrumentFaultInputs.key(key);
        long version=DirectoryInputs.version(input.expectedVersion()), assetVersion=DirectoryInputs.version(input.expectedAssetVersion());
        String note=DirectoryInputs.memo(input.resolutionNote());
        String route="/api/v1/instrument-faults/"+id+"/resolve";
        String fingerprint=InstrumentFaultInputs.digest("POST|"+route+"|"+version+"|"+assetVersion+"|"+(note == null ? "" : note));
        return writes.execute(status -> {
            guard.lock(); access.administratorForMutation(actorId);
            InstrumentFaultRepository.Replay replay=faults.replay(actorId,digest).orElse(null);
            if (replay != null) {
                requireFingerprint(replay,fingerprint);
                return faults.response(replay.incidentId());
            }
            InstrumentFault planned=faults.find(id).orElseThrow(DirectoryInputs::notFound);
            Instrument asset=faults.lockAsset(planned.assetId()).orElseThrow(DirectoryInputs::notFound);
            InstrumentFault incident=faults.lock(id).orElseThrow(DirectoryInputs::notFound);
            if (!incident.open()) { throw conflict("INSTRUMENT_FAULT_RESOLVED","이미 복구된 고장 이력입니다. 최신 상태를 확인해 주세요."); }
            DirectoryInputs.sameVersion(incident.version(),version);
            DirectoryInputs.sameVersion(asset.version(),assetVersion);
            if (!asset.faulty()) { throw conflict("VERSION_CONFLICT","악기 상태가 변경되었습니다. 새로고침 후 다시 시도해 주세요."); }
            Instant now=now();
            faults.assetStatus(asset.id(),"NORMAL");
            faults.resolve(id,actorId,note,now);
            faults.remember(actorId,digest,fingerprint,route,200,id,now);
            return faults.response(id);
        });
    }
    private static void requireFingerprint(InstrumentFaultRepository.Replay replay, String fingerprint) {
        if (!replay.fingerprint().equals(fingerprint)) {
            throw conflict("IDEMPOTENCY_KEY_REUSED","같은 요청 키를 다른 작업에 사용할 수 없습니다.");
        }
    }
    private static ApiException conflict(String code, String message) { return new ApiException(409,code,message); }
    private Instant now() { return clock.instant().truncatedTo(ChronoUnit.MICROS); }
}
