package com.amalitech.labresultsvalidator.domain.standup.gate;

public record ReferenceSummary(
    int specializationCount,
    int moduleCount,
    int labCount,
    int learnerCount,
    int instructorCount
) {}
