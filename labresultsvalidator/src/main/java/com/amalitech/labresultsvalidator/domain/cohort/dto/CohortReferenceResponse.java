package com.amalitech.labresultsvalidator.domain.cohort.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CohortReferenceResponse {
    private List<SpecializationWithModulesResponse> specializations;
    private List<LearnerResponse> learners;
    private List<InstructorContactResponse> instructors;
}
