package com.amalitech.labresultsvalidator.domain.standup.gate;

import java.util.List;

public record ValidatedReferenceBundle(
    List<SpecializationRow> specializations,
    List<ModuleRow> modules,
    List<LabRow> labs,
    List<LearnerRow> learners,
    List<InstructorRow> instructors
) {
    public record SpecializationRow(String specializationId, String name) {}

    public record ModuleRow(String moduleId, String name, String specializationId) {}

    public record LabRow(String assessmentId, String labTitle, String moduleId) {}

    public record LearnerRow(String email, String fullName, String specialization) {}

    /** {@code specialization} is already resolved to its canonical Specializations-file name. */
    public record InstructorRow(String fullName, String email, String specialization) {}
}
