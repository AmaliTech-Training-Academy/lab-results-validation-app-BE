package com.amalitech.labresultsvalidator.domain.reference.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LearnerResponse {
    private UUID id;
    private String fullName;
    private String email;
    private UUID cohortId;
    private UUID specializationId;
    private String status;
}
