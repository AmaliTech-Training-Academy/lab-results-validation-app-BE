package com.amalitech.labresultsvalidator.domain.specialization.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SpecializationResponse {
    private UUID id;
    private UUID cohortId;
    private String cohortName;
    private String name;
    private String code;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
