package com.amalitech.labresultsvalidator.domain.lab.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabResponse {
    private UUID id;
    private UUID moduleId;
    private String moduleName;
    private String title;
    private BigDecimal maxScore;
    private boolean immutable;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
