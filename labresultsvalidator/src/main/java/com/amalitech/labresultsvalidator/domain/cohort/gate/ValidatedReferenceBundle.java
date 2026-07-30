package com.amalitech.labresultsvalidator.domain.cohort.gate;

import java.util.List;

public record ValidatedReferenceBundle(
    List<SpecializationRow> specializations,
    List<ModuleRow> modules,
    List<LabRow> labs,
    List<LearnerRow> learners,
    boolean quizReferencePresent
) {
    public record SpecializationRow(String specializationId, String name) {}

    public record ModuleRow(String moduleId, String name, String specializationId) {}

    public record LabRow(String assessmentId, String labTitle, String moduleId) {}

    public record LearnerRow(String email, String fullName, String specialization) {}
}
