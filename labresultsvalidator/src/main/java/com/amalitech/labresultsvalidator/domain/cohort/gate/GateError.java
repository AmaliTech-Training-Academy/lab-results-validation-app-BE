package com.amalitech.labresultsvalidator.domain.cohort.gate;

public record GateError(String file, String location, String rule, String message) {
}
