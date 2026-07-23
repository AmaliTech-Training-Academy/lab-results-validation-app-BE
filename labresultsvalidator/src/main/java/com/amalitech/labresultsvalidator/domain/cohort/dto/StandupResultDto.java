package com.amalitech.labresultsvalidator.domain.cohort.dto;

import com.amalitech.labresultsvalidator.domain.cohort.gate.ReferenceSummary;

public record StandupResultDto(
    GateStateDto gate1,
    GateStateDto gate2,
    GateStateDto gate3,
    ReferenceSummary summary
) {}
