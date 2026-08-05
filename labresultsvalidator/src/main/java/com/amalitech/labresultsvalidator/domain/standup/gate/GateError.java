package com.amalitech.labresultsvalidator.domain.standup.gate;

public record GateError(String file, String location, String rule, String message) {
}
