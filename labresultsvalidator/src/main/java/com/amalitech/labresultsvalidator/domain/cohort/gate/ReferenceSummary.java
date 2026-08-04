package com.amalitech.labresultsvalidator.domain.cohort.gate;

public record ReferenceSummary(
    int specializationCount,
    int moduleCount,
    int labCount,
    int learnerCount,
    int instructorCount
) {}
