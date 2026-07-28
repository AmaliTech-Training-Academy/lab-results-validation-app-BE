package com.amalitech.labresultsvalidator.domain.cohort.gate;

import java.util.List;

public record GateResult(boolean passed, List<GateError> errors) {

    public static GateResult pass() {
        return new GateResult(true, List.of());
    }

    public static GateResult fail(List<GateError> errors) {
        return new GateResult(false, errors);
    }

    public static GateResult fail(String file, String location, String rule, String message) {
        return fail(List.of(new GateError(file, location, rule, message)));
    }
}
