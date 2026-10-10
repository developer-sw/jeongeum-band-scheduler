package kr.jeongeum.scheduler.reservation.api;

import kr.jeongeum.scheduler.reservation.domain.Instrument;

public record InstrumentResponse(String code, String name, String status, long version) {
    public static InstrumentResponse from(Instrument instrument) {
        return new InstrumentResponse(instrument.code(), instrument.name(), instrument.status(), instrument.version());
    }
}
