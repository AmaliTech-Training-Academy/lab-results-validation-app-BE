package com.amalitech.labresultsvalidator.domain.lab.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@NoArgsConstructor
public class CreateLabRequest {

    @NotNull(message = "Module ID is required")
    private UUID moduleId;

    @NotBlank(message = "Lab title is required")
    @Size(max = 200, message = "Lab title must not exceed 200 characters")
    private String title;

    @NotNull(message = "Max score is required")
    @Positive(message = "Max score must be greater than 0")
    private BigDecimal maxScore;
}
