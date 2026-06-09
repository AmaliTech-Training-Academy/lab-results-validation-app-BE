package com.amalitech.labresultsvalidator.domain.lab.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor
public class PatchLabRequest {

    @Size(max = 200, message = "Lab title must not exceed 200 characters")
    private String title;

    @Positive(message = "Max score must be greater than 0")
    private BigDecimal maxScore;

    private Boolean immutable;
}
