package com.amalitech.labresultsvalidator.domain.standup.dto;

import com.amalitech.labresultsvalidator.domain.standup.gate.GateError;

import java.util.List;

public record GateStateDto(String state, List<GateError> errors) {

    public static GateStateDto pending() {
        return new GateStateDto("PENDING", List.of());
    }

    public static GateStateDto passed() {
        return new GateStateDto("PASSED", List.of());
    }

    public static GateStateDto failed(List<GateError> errors) {
        return new GateStateDto("FAILED", errors);
    }
}
