package com.amalitech.labresultsvalidator.domain.cohort.gate;

import java.util.List;

public record ValidatedReferenceBundle(
    List<SpecializationRow> specializations,
    List<ModuleRow> modules,
    List<LabRow> labs,
    List<LearnerRow> learners,
    List<InstructorContactRow> instructors
) {
    public record SpecializationRow(String name, String code) {}

    public record ModuleRow(String name, String code, int sequence, String specializationCode) {}

    public record LabRow(String title, String moduleCode) {}

    public record LearnerRow(String learnerId, String fullName, String email, String specializationCode) {}

    public record InstructorContactRow(String instructorId, String fullName, String email) {}
}
