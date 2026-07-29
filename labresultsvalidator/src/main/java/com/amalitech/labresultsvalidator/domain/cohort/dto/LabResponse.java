package com.amalitech.labresultsvalidator.domain.cohort.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabResponse {
    private UUID id;
    private UUID moduleId;
    private String title;
    private BigDecimal maxScore;
}
