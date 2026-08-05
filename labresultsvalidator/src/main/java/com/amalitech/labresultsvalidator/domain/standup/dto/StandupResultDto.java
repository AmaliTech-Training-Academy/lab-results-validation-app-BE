package com.amalitech.labresultsvalidator.domain.standup.dto;

import com.amalitech.labresultsvalidator.domain.standup.gate.ReferenceSummary;

public record StandupResultDto(
    GateStateDto gate1,
    GateStateDto gate2,
    GateStateDto gate3,
    ReferenceSummary summary
) {}
